use super::error::OID4VPError;
use super::permission_request::*;
use crate::common::*;
use crate::credential::*;
use crate::vdc_collection::VdcCollection;

use std::sync::Arc;

use openid4vp::core::authorization_request::parameters::ClientIdScheme;
use openid4vp::core::credential_format::{ClaimFormatDesignation, ClaimFormatPayload};
use openid4vp::core::presentation_definition::PresentationDefinition;
use openid4vp::core::response::parameters::VpTokenItem;
use openid4vp::{
    core::{
        authorization_request::{
            parameters::ResponseMode,
            verification::{did::verify_with_resolver, RequestVerifier},
            AuthorizationRequestObject,
        },
        metadata::WalletMetadata,
        presentation_submission::{DescriptorMap, PresentationSubmission},
        response::{parameters::VpToken, AuthorizationResponse, UnencodedAuthorizationResponse},
    },
    wallet::Wallet as OID4VPWallet,
};
use ssi::claims::vc::v1::syntax::JsonPresentation;
use ssi::dids::DIDWeb;
use ssi::dids::VerificationMethodDIDResolver;
use ssi::json_ld::iref::UriBuf;
use ssi::prelude::AnyJwkMethod;
use uniffi::deps::{anyhow, log};

/// A Holder is an entity that possesses one or more Verifiable Credentials.
/// The Holder is typically the subject of the credentials, but not always.
/// The Holder has the ability to generate Verifiable Presentations from
/// these credentials and share them with Verifiers.
#[derive(Debug, uniffi::Object)]
pub struct Holder {
    /// An atomic reference to the VDC collection.
    pub(crate) vdc_collection: Option<Arc<VdcCollection>>,

    /// Metadata about the holder.
    pub(crate) metadata: WalletMetadata,

    /// HTTP Request Client
    pub(crate) client: openid4vp::core::util::ReqwestClient,

    /// A list of trusted DIDs.
    pub(crate) trusted_dids: Vec<String>,

    /// Provide optional credentials to the holder instance.
    pub(crate) provided_credentials: Option<Vec<Arc<ParsedCredential>>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Holder {
    /// Uses VDC collection to retrieve the credentials for a given presentation definition.
    #[uniffi::constructor]
    pub async fn new(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
    ) -> Result<Arc<Self>, OID4VPError> {
        let client = openid4vp::core::util::ReqwestClient::new()
            .map_err(|e| OID4VPError::HttpClientInitialization(format!("{e:?}")))?;

        Ok(Arc::new(Self {
            client,
            vdc_collection: Some(vdc_collection),
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: None,
        }))
    }

    /// Construct a new holder with provided credentials
    /// instead of a VDC collection.
    ///
    /// This constructor will use the provided credentials for the presentation,
    /// instead of searching for credentials in the VDC collection.
    #[uniffi::constructor]
    pub async fn new_with_credentials(
        provided_credentials: Vec<Arc<ParsedCredential>>,
        trusted_dids: Vec<String>,
    ) -> Result<Arc<Self>, OID4VPError> {
        let client = openid4vp::core::util::ReqwestClient::new()
            .map_err(|e| OID4VPError::HttpClientInitialization(format!("{e:?}")))?;

        Ok(Arc::new(Self {
            client,
            vdc_collection: None,
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: Some(provided_credentials),
        }))
    }

    /// Given an authorization request URL, return a permission request,
    /// which provides a list of requested credentials and requested fields
    /// that align with the presentation definition of the request.
    ///
    /// This will fetch the presentation definition from the verifier.
    pub async fn authorization_request(
        &self,
        url: Url,
    ) -> Result<Arc<PermissionRequest>, OID4VPError> {
        let request = self
            .validate_request(url)
            .await
            .map_err(|e| OID4VPError::RequestValidation(format!("{e:?}")))?;

        match request.response_mode() {
            ResponseMode::DirectPost | ResponseMode::DirectPostJwt => {
                self.permission_request(request).await
            }
            ResponseMode::Unsupported(mode) => {
                Err(OID4VPError::UnsupportedResponseMode(mode.to_owned()))
            }
        }
    }

    pub async fn submit_permission_response(
        &self,
        response: Arc<PermissionResponse>,
    ) -> Result<Option<Url>, OID4VPError> {
        // Create a descriptor map for the presentation submission based on the credentials
        // returned from the selection response.
        let Some(input_descriptor_id) = response
            .presentation_definition
            .input_descriptors()
            .first()
            .map(|d| d.id().to_owned())
        else {
            // NOTE: We may wish to add a more generic `BadRequest` error type
            // we should always expect to have at least one input descriptor.
            return Err(OID4VPError::InputDescriptorNotFound);
        };

        let descriptor_map =
            self.create_descriptor_map(&response.selected_credential, input_descriptor_id);

        let presentation_submission_id = uuid::Uuid::new_v4();
        let presentation_definition_id = response.presentation_definition.id().clone();

        // // Create a presentation submission.
        let presentation_submission = PresentationSubmission::new(
            presentation_submission_id,
            presentation_definition_id,
            vec![descriptor_map],
        );

        let vp_token = self
            .create_verifiable_presentation(&response.selected_credential)
            .await?;

        let response = self
            .submit_response(
                response.authorization_request.clone(),
                AuthorizationResponse::Unencoded(UnencodedAuthorizationResponse(
                    Default::default(),
                    vp_token,
                    presentation_submission,
                )),
            )
            .await
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?;

        Ok(response)
    }
}

// Internal methods for the Holder.
impl Holder {
    /// Return the static metadata for the holder.
    ///
    /// This method is used to initialize the metadata for the holder.
    pub(crate) fn metadata() -> Result<WalletMetadata, OID4VPError> {
        let mut metadata = WalletMetadata::openid4vp_scheme_static();

        // Insert support for the VCDM2 SD JWT format.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::Other("vcdm2_sd_jwt".into()),
            ClaimFormatPayload::AlgValuesSupported(vec!["ES256".into()]),
        );

        metadata
            // Insert support for the DID client ID scheme.
            .add_client_id_schemes_supported(ClientIdScheme::Did)
            .map_err(|e| OID4VPError::MetadataInitialization(format!("{e:?}")))?;

        Ok(metadata)
    }

    /// This will return all the credentials that match the presentation definition.
    async fn search_credentials_vs_presentation_definition(
        &self,
        definition: &PresentationDefinition,
    ) -> Result<Vec<Arc<ParsedCredential>>, OID4VPError> {
        let credentials = match &self.provided_credentials {
            // Use a pre-selected list of credentials if provided.
            Some(credentials) => credentials.to_owned(),
            None => match &self.vdc_collection {
                None => vec![],
                Some(vdc_collection) => vdc_collection
                    .all_entries()?
                    .into_iter()
                    .filter_map(|id| {
                        vdc_collection
                            .get(id)
                            .ok()
                            .flatten()
                            .and_then(|cred| cred.try_into_parsed().ok())
                    })
                    .collect::<Vec<Arc<ParsedCredential>>>(),
            },
        }
        .into_iter()
        .filter_map(
            |cred| match cred.check_presentation_definition(definition) {
                true => Some(cred),
                false => None,
            },
        )
        .collect::<Vec<Arc<ParsedCredential>>>();

        Ok(credentials)
    }

    // Construct a DescriptorMap for the presentation submission based on the
    // credentials returned from the VDC collection.
    fn create_descriptor_map(
        &self,
        credential: &Arc<ParsedCredential>,
        input_descriptor_id: String,
    ) -> DescriptorMap {
        DescriptorMap::new(
            input_descriptor_id,
            credential.format().to_string().as_str(),
            "$.verifiableCredential".into(),
        )
    }

    // Internal method for returning the `PermissionRequest` for an oid4vp request.
    async fn permission_request(
        &self,
        request: AuthorizationRequestObject,
    ) -> Result<Arc<PermissionRequest>, OID4VPError> {
        // Resolve the presentation definition.
        let presentation_definition = request
            .resolve_presentation_definition(self.http_client())
            .await
            .map_err(|e| OID4VPError::PresentationDefinitionResolution(format!("{e:?}")))?
            .into_parsed();

        let credentials = self
            .search_credentials_vs_presentation_definition(&presentation_definition)
            .await?;

        Ok(PermissionRequest::new(
            presentation_definition.clone(),
            credentials.clone(),
            request,
        ))
    }

    async fn create_verifiable_presentation(
        &self,
        credential: &Arc<ParsedCredential>,
    ) -> Result<VpToken, OID4VPError> {
        match &credential.inner {
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => {
                // TODO: need to provide the "filtered" (disclosed) fields of the
                // credential to be encoded into the VpToken.
                //
                // Currently, this is encoding the entire revealed SD-JWT,
                // without the selection of individual disclosed fields.
                //
                // We need to selectively disclosed fields.
                let compact: &str = sd_jwt.inner.as_ref();
                Ok(VpTokenItem::String(compact.to_string()).into())
            }
            ParsedCredentialInner::JwtVcJson(vc) => {
                let id =
                    UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec()).ok();

                // TODO: determine how the holder ID should be set.
                let holder_id = None;

                // NOTE: JwtVc types are ALWAYS VCDM 1.1, therefore using the v1::syntax::JsonPresentation
                // type.
                let token = VpTokenItem::from(JsonPresentation::new(
                    id,
                    holder_id,
                    vec![vc.credential().to_owned()],
                ));

                Ok(token.into())
            }
            _ => Err(OID4VPError::VpTokenParse(format!(
                "Credential parsing for VP Token is not implemented for {:?}.",
                credential,
            ))),
        }
    }
}

#[async_trait::async_trait]
impl RequestVerifier for Holder {
    /// Performs verification on Authorization Request Objects when `client_id_scheme` is `did`.
    async fn did(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: String,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying DID request.");

        let resolver: VerificationMethodDIDResolver<DIDWeb, AnyJwkMethod> =
            VerificationMethodDIDResolver::new(DIDWeb);

        verify_with_resolver(
            &self.metadata,
            decoded_request,
            request_jwt,
            Some(self.trusted_dids.as_slice()),
            &resolver,
        )
        .await?;

        Ok(())
    }
}

impl OID4VPWallet for Holder {
    type HttpClient = openid4vp::core::util::ReqwestClient;

    fn http_client(&self) -> &Self::HttpClient {
        &self.client
    }

    fn metadata(&self) -> &WalletMetadata {
        &self.metadata
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use vcdm2_sd_jwt::VCDM2SdJwt;

    // NOTE: This test requires the `companion` service to be running and
    // available at localhost:3000.
    //
    // See: https://github.com/spruceid/companion/pull/1
    #[ignore]
    #[tokio::test]
    async fn test_oid4vp_url() -> Result<(), Box<dyn std::error::Error>> {
        let example_sd_jwt = include_str!("../../tests/examples/sd_vc.jwt");
        let sd_jwt = VCDM2SdJwt::new_from_compact_sd_jwt(example_sd_jwt.into())?;
        let credential = ParsedCredential::new_sd_jwt(sd_jwt);

        let initiate_api = "http://localhost:3000/api/oid4vp/initiate";

        // Make a request to the OID4VP initiate API.
        // provide a url-encoded `format` parameter to specify the format of the presentation.
        let response: (String, String) = reqwest::Client::new()
            .post(initiate_api)
            .form(&[("format", "sd_jwt")])
            .send()
            .await?
            .json()
            .await?;

        let _id = response.0;
        let url = Url::parse(&response.1).expect("failed to parse url");

        println!("Authorization URL: {url:?}");

        // Make a request to the OID4VP URL.
        let holder = Holder::new_with_credentials(
            vec![credential],
            vec!["did:web:localhost%3A3000:oid4vp:client".into()],
        )
        .await?;

        let permission_request = holder.authorization_request(url).await?;

        let mut parsed_credentials = permission_request.credentials();

        assert_eq!(parsed_credentials.len(), 1);

        let selected_credential = parsed_credentials
            .pop()
            .expect("failed to retrieve a parsed credential matching the presentation definition");

        let requested_fields = permission_request.requested_fields(&selected_credential);

        println!("Requested Fields: {requested_fields:?}");

        assert!(requested_fields.len() > 0);

        let response = permission_request.create_permission_response(selected_credential);

        holder.submit_permission_response(response).await?;

        Ok(())
    }

    #[tokio::test]
    async fn test_vehicle_title() -> Result<(), Box<dyn std::error::Error>> {
        Ok(())
    }
}
