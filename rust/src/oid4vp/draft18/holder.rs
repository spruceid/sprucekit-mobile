#![allow(deprecated)]

use super::credential::{Draft18PresentableCredential, ParsedCredentialDraft18Ext};
use super::error::Draft18OID4VPError;
use super::permission_request::*;
use super::presentation::Draft18PresentationSigner;
use crate::credential::ParsedCredential;
use crate::vdc_collection::VdcCollection;

use std::collections::HashMap;
use std::sync::Arc;

use anyhow::Context;
use futures::StreamExt;
use openidvp_draft18::core::authorization_request::parameters::ClientIdScheme;
use openidvp_draft18::core::credential_format::{ClaimFormatDesignation, ClaimFormatPayload};
use openidvp_draft18::core::input_descriptor::ConstraintsLimitDisclosure;
use openidvp_draft18::core::presentation_definition::PresentationDefinition;
use openidvp_draft18::{
    core::{
        authorization_request::{
            parameters::ResponseMode,
            verification::{did::verify_with_resolver, RequestVerifier},
            AuthorizationRequestObject,
        },
        metadata::WalletMetadata,
    },
    wallet::Wallet as OID4VPWallet,
};

use ssi::dids::DIDKey;
use ssi::dids::DIDWeb;
use ssi::dids::VerificationMethodDIDResolver;
use ssi::prelude::AnyJwkMethod;
use url::Url;

pub enum Draft18AuthRequest {
    /// Parse the incoming string as a URL.
    Url(Url),
    /// Parse the incoming string as a JSON-string encoded Authorization Request Object.
    Request(Box<AuthorizationRequestObject>),
}

uniffi::custom_type!(Draft18AuthRequest, String, {
    try_lift: |value| {
        match Url::parse(&value) {
            Ok(url) => Ok(Draft18AuthRequest::Url(url)),
            Err(_) => {
                let req: AuthorizationRequestObject = serde_json::from_str(&value)
                    .map_err(|e| anyhow::anyhow!("Failed to parse JSON: {:?}", e))?;
                Ok(Draft18AuthRequest::Request(Box::new(req)))
            }
        }
    },
    lower: |req| {
        match req {
            Draft18AuthRequest::Url(url) => url.to_string(),
            Draft18AuthRequest::Request(req) => serde_json::to_string(&req)
                // SAFETY: The authorization request object is a valid JSON object.
                .unwrap(),
        }
    },
});

/// A Holder is an entity that possesses one or more Verifiable Credentials.
/// The Holder is typically the subject of the credentials, but not always.
/// The Holder has the ability to generate Verifiable Presentations from
/// these credentials and share them with Verifiers.
#[deprecated(
    note = "Legacy draft-18 compatibility only. Prefer OID4VP v1 APIs for new integrations; this API may be removed in a future release."
)]
#[derive(Debug, uniffi::Object)]
pub struct Draft18Holder {
    /// An atomic reference to the VDC collection.
    pub(crate) vdc_collection: Option<Arc<VdcCollection>>,

    /// Metadata about the holder.
    pub(crate) metadata: WalletMetadata,

    /// HTTP Request Client
    pub(crate) client: openidvp_draft18::core::util::ReqwestClient,

    /// A list of trusted DIDs.
    pub(crate) trusted_dids: Vec<String>,

    /// Provide optional credentials to the holder instance.
    pub(crate) provided_credentials: Option<Vec<Arc<ParsedCredential>>>,

    /// Foreign Interface for the [Draft18PresentationSigner]
    pub(crate) signer: Arc<Box<dyn Draft18PresentationSigner>>,

    /// Optional context map for resolving specific contexts
    pub(crate) context_map: Option<HashMap<String, String>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Draft18Holder {
    /// Uses VDC collection to retrieve the credentials for a given presentation definition.
    #[uniffi::constructor]
    pub async fn new(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn Draft18PresentationSigner>,
        context_map: Option<HashMap<String, String>>,
    ) -> Result<Arc<Self>, Draft18OID4VPError> {
        let client = openidvp_draft18::core::util::ReqwestClient::new()
            .map_err(|e| Draft18OID4VPError::HttpClientInitialization(format!("{e:?}")))?;

        Ok(Arc::new(Self {
            client,
            vdc_collection: Some(vdc_collection),
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: None,
            signer: Arc::new(signer),
            context_map,
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
        signer: Box<dyn Draft18PresentationSigner>,
        context_map: Option<HashMap<String, String>>,
    ) -> Result<Arc<Self>, Draft18OID4VPError> {
        let client = openidvp_draft18::core::util::ReqwestClient::new()
            .map_err(|e| Draft18OID4VPError::HttpClientInitialization(format!("{e:?}")))?;

        Ok(Arc::new(Self {
            client,
            vdc_collection: None,
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: Some(provided_credentials),
            signer: Arc::new(signer),
            context_map,
        }))
    }

    /// Given an authorization request URL, return a permission request,
    /// which provides a list of requested credentials and requested fields
    /// that align with the presentation definition of the request.
    ///
    /// This will fetch the presentation definition from the verifier.
    pub async fn authorization_request(
        &self,
        req: Draft18AuthRequest,
    ) -> Result<Arc<Draft18PermissionRequest>, Draft18OID4VPError> {
        let request = match req {
            Draft18AuthRequest::Url(mut url) => {
                // NOTE: Replace the host value with an empty string to remove any
                // leading host value before the query.
                url.set_host(Some(""))
                    .map_err(|e| Draft18OID4VPError::RequestValidation(format!("{e:?}")))?;

                self.validate_request(url)
                    .await
                    .map_err(|e| Draft18OID4VPError::RequestValidation(format!("{e:?}")))?
            }
            Draft18AuthRequest::Request(req) => *req,
        };

        match request.response_mode() {
            ResponseMode::DirectPost | ResponseMode::DirectPostJwt => {
                self.permission_request(request).await
            }
            mode => Err(Draft18OID4VPError::UnsupportedResponseMode(
                mode.to_string(),
            )),
        }
    }

    pub async fn submit_permission_response(
        &self,
        response: Arc<Draft18PermissionResponse>,
    ) -> Result<Option<Url>, Draft18OID4VPError> {
        let auth_response = response.authorization_response()?;

        self.submit_response(response.authorization_request.clone(), auth_response)
            .await
            .map_err(|e| Draft18OID4VPError::ResponseSubmission(format!("{e:?}")))
    }
}

// Internal methods for the Holder.
impl Draft18Holder {
    /// Return the static metadata for the holder.
    ///
    /// This method is used to initialize the metadata for the holder.
    pub(crate) fn metadata() -> Result<WalletMetadata, Draft18OID4VPError> {
        let mut metadata = WalletMetadata::openid4vp_scheme_static();

        // Insert support for the VCDM2 SD JWT format.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::Other("vcdm2_sd_jwt".into()),
            ClaimFormatPayload::AlgValuesSupported(vec!["ES256".into()]),
        );

        // Insert support for the JSON-LD format.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::LdpVp,
            ClaimFormatPayload::ProofType(vec!["ecdsa-rdfc-2019".into()]),
        );

        // Insert support for JwtVpJson format.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::JwtVpJson,
            ClaimFormatPayload::AlgValuesSupported(vec!["ES256".into()]),
        );

        metadata
            // Insert support for the DID client ID scheme.
            .add_client_id_schemes_supported(&[
                ClientIdScheme(ClientIdScheme::DID.to_string()),
                ClientIdScheme(ClientIdScheme::REDIRECT_URI.to_string()),
            ])
            .map_err(|e| Draft18OID4VPError::MetadataInitialization(format!("{e:?}")))?;

        metadata
            // Allow unencoded requested.
            .add_request_object_signing_alg_values_supported(ssi::jwk::Algorithm::None)
            .map_err(|e| Draft18OID4VPError::MetadataInitialization(format!("{e:?}")))?;

        Ok(metadata)
    }

    /// This will return all the credentials that match the presentation definition.
    async fn search_credentials_vs_presentation_definition(
        &self,
        definition: &mut PresentationDefinition,
    ) -> Result<Vec<Arc<ParsedCredential>>, Draft18OID4VPError> {
        let credentials = match &self.provided_credentials {
            // Use a pre-selected list of credentials if provided.
            Some(credentials) => credentials.to_owned(),
            None => match &self.vdc_collection {
                None => vec![],
                Some(vdc_collection) => {
                    futures::stream::iter(vdc_collection.all_entries().await?)
                        .filter_map(|id| async move {
                            vdc_collection
                                .get(id)
                                .await
                                .ok()
                                .flatten()
                                .and_then(|cred| cred.try_into_parsed().ok())
                        })
                        .collect::<Vec<Arc<ParsedCredential>>>()
                        .await
                }
            },
        }
        .into_iter()
        .filter_map(
            |cred| match cred.satisfies_presentation_definition(definition) {
                true => Some(cred),
                false => None,
            },
        )
        .collect::<Vec<Arc<ParsedCredential>>>();

        Ok(credentials)
    }

    // Internal method for returning the `Draft18PermissionRequest` for an oid4vp request.
    async fn permission_request(
        &self,
        request: AuthorizationRequestObject,
    ) -> Result<Arc<Draft18PermissionRequest>, Draft18OID4VPError> {
        // Resolve the presentation definition.
        let mut presentation_definition = request
            .resolve_presentation_definition(self.http_client())
            .await
            .map_err(|e| Draft18OID4VPError::PresentationDefinitionResolution(format!("{e:?}")))?
            .context("request object does not contain a presentation definition")
            .map_err(|e| Draft18OID4VPError::PresentationDefinitionResolution(format!("{e:?}")))?
            .into_parsed();

        let credentials = self
            .search_credentials_vs_presentation_definition(&mut presentation_definition)
            .await?;

        if credentials.is_empty() {
            return Err(Draft18OID4VPError::Draft18PermissionRequest(
                Draft18PermissionRequestError::NoCredentialsFound,
            ));
        }

        // TODO: Add full support for limit_disclosure, probably this should be thrown at OID4VP
        if presentation_definition
            .input_descriptors()
            .iter()
            .any(|id| {
                id.constraints
                    .limit_disclosure()
                    .is_some_and(|ld| matches!(ld, ConstraintsLimitDisclosure::Required))
            })
        {
            log::debug!("Limit disclosure required for input descriptor.");

            return Err(Draft18OID4VPError::LimitDisclosure(
                "Limit disclosure required for input descriptor.".to_string(),
            ));
        }

        let credentials = credentials
            .into_iter()
            .map(|c| {
                let input_descriptor_id = presentation_definition
                    .input_descriptors()
                    .iter()
                    .find(|_| !c.requested_fields(&presentation_definition).is_empty())
                    .map(|descriptor| descriptor.id.clone())
                    // SAFETY: the credential will always match at least one input descriptor
                    // at this point.
                    .unwrap();

                Arc::new(Draft18PresentableCredential {
                    inner: c.inner.clone(),
                    limit_disclosure: presentation_definition.input_descriptors().iter().any(
                        |descriptor| {
                            !c.requested_fields(&presentation_definition).is_empty()
                                && matches!(
                                    descriptor.constraints.limit_disclosure(),
                                    Some(ConstraintsLimitDisclosure::Required)
                                )
                        },
                    ),
                    selected_fields: None,
                    input_descriptor_id,
                })
            })
            .collect::<Vec<_>>();

        Ok(Draft18PermissionRequest::new(
            presentation_definition.clone(),
            credentials.clone(),
            request,
            self.signer.clone(),
            self.context_map.clone(),
        ))
    }
}

#[async_trait::async_trait]
impl RequestVerifier for Draft18Holder {
    /// Performs verification on Authorization Request Objects
    /// when `client_id_scheme` is `did`.
    async fn did(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying DID request.");

        let request_jwt = request_jwt.context("request JWT is required for did verification")?;

        let resolver: VerificationMethodDIDResolver<DIDWeb, AnyJwkMethod> =
            VerificationMethodDIDResolver::new(DIDWeb);

        let trusted_dids = match self.trusted_dids.as_slice() {
            [] => None,
            dids => Some(dids),
        };

        verify_with_resolver(
            &self.metadata,
            decoded_request,
            request_jwt,
            trusted_dids,
            &resolver,
        )
        .await?;

        Ok(())
    }

    /// Performs verification on Authorization Request Objects when `client_id_scheme` is `redirect_uri`.
    async fn redirect_uri(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying redirect_uri request.");

        let request_jwt = request_jwt.context("request JWT is required for did verification")?;

        let resolver: VerificationMethodDIDResolver<DIDKey, AnyJwkMethod> =
            VerificationMethodDIDResolver::new(DIDKey);

        let trusted_dids = match self.trusted_dids.as_slice() {
            [] => None,
            dids => Some(dids),
        };

        verify_with_resolver(
            &self.metadata,
            decoded_request,
            request_jwt,
            trusted_dids,
            &resolver,
        )
        .await?;

        Ok(())
    }
}

impl OID4VPWallet for Draft18Holder {
    type HttpClient = openidvp_draft18::core::util::ReqwestClient;

    fn http_client(&self) -> &Self::HttpClient {
        &self.client
    }

    fn metadata(&self) -> &WalletMetadata {
        &self.metadata
    }
}
