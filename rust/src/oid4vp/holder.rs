use super::error::OID4VPError;
use super::permission_request::*;
use super::presentation::PresentationSigner;
use crate::credential::*;
use crate::crypto::KeyStore;
use crate::vdc_collection::VdcCollection;

use std::collections::HashMap;
use std::sync::Arc;

use anyhow::Context;
use futures::StreamExt;
use openid4vp::core::authorization_request::parameters::ClientIdScheme;
use openid4vp::core::credential_format::{ClaimFormatDesignation, ClaimFormatPayload};
use openid4vp::core::dcql_query::DcqlQuery;
use openid4vp::{
    core::{
        authorization_request::{
            parameters::ResponseMode,
            verification::{
                did::verify_with_resolver, verifier::P256Verifier, x509_hash, x509_san,
                RequestVerifier,
            },
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
use uniffi::deps::anyhow;
use url::Url;

pub enum AuthRequest {
    /// Parse the incoming string as a URL.
    Url(Url),
    /// Parse the incoming string as a JSON-string encoded Authorization Request Object.
    Request(Box<AuthorizationRequestObject>),
}
uniffi::custom_type!(AuthRequest, String, {
    try_lift: |value| {
match Url::parse(&value) {
    Ok(url) => Ok(AuthRequest::Url(url)),
    Err(_) => {
        let req: AuthorizationRequestObject = serde_json::from_str(&value)
            .map_err(|e| anyhow::anyhow!("Failed to parse JSON: {:?}", e))?;
        Ok(AuthRequest::Request(Box::new(req)))
    }
}
    },
    lower: |req| {
match req {
    AuthRequest::Url(url) => url.to_string(),
    AuthRequest::Request(req) => serde_json::to_string(&req)
        // SAFETY: The authorization request object is a valid JSON object.
        .unwrap(),
}
    },
});

/// A Holder is an entity that possesses one or more Verifiable Credentials.
/// The Holder is typically the subject of the credentials, but not always.
/// The Holder has the ability to generate Verifiable Presentations from
/// these credentials and share them with Verifiers.
#[derive(uniffi::Object)]
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

    /// Foreign Interface for the [PresentationSigner]
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,

    /// Optional context map for resolving specific contexts
    pub(crate) context_map: Option<HashMap<String, String>>,

    /// Optional KeyStore for mdoc credential signing
    pub(crate) keystore: Option<Arc<dyn KeyStore>>,
}

impl std::fmt::Debug for Holder {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Holder")
            .field("vdc_collection", &self.vdc_collection)
            .field("metadata", &self.metadata)
            .field("trusted_dids", &self.trusted_dids)
            .field("provided_credentials", &self.provided_credentials)
            .field("keystore", &self.keystore.as_ref().map(|_| "KeyStore"))
            .finish()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Holder {
    /// Uses VDC collection to retrieve the credentials for a given presentation definition.
    #[uniffi::constructor]
    pub async fn new(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn PresentationSigner>,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, OID4VPError> {
        let client = openid4vp::core::util::ReqwestClient::new()
            .map_err(|e| OID4VPError::HttpClientInitialization(format!("{e:?}")))?;

        Ok(Arc::new(Self {
            client,
            vdc_collection: Some(vdc_collection),
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: None,
            signer: Arc::new(signer),
            context_map,
            keystore,
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
        signer: Box<dyn PresentationSigner>,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, OID4VPError> {
        let client = openid4vp::core::util::ReqwestClient::new()
            .map_err(|e| OID4VPError::HttpClientInitialization(format!("{e:?}")))?;

        Ok(Arc::new(Self {
            client,
            vdc_collection: None,
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: Some(provided_credentials),
            signer: Arc::new(signer),
            context_map,
            keystore,
        }))
    }

    /// Given an authorization request URL, return a permission request,
    /// which provides a list of requested credentials and requested fields
    /// that align with the presentation definition of the request.
    ///
    /// This will fetch the presentation definition from the verifier.
    pub async fn authorization_request(
        &self,
        req: AuthRequest,
        // Callback here to allow for review of untrusted DIDs.
    ) -> Result<Arc<PermissionRequest>, OID4VPError> {
        let request = match req {
            AuthRequest::Url(mut url) => {
                // NOTE: Replace the host value with an empty string to remove any
                // leading host value before the query.
                url.set_host(Some(""))
                    .map_err(|e| OID4VPError::RequestValidation(format!("{e:?}")))?;

                self.validate_request(url)
                    .await
                    .map_err(|e| OID4VPError::RequestValidation(format!("{e:?}")))?
            }
            AuthRequest::Request(req) => *req,
        };

        match request.response_mode() {
            ResponseMode::DirectPost | ResponseMode::DirectPostJwt => {
                self.permission_request(request).await
            }
            mode => Err(OID4VPError::UnsupportedResponseMode(mode.to_string())),
        }
    }

    pub async fn submit_permission_response(
        &self,
        response: Arc<PermissionResponse>,
    ) -> Result<Option<Url>, OID4VPError> {
        let auth_response = response.authorization_response()?;

        self.submit_response(response.authorization_request.clone(), auth_response)
            .await
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))
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
            ClaimFormatPayload::AlgValues(vec!["ES256".into()]),
        );

        // Insert support for the JSON-LD format.
        // Per OID4VP v1.0 Section B.1.3.2.1, ldp_vc covers both credentials and presentations.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::LdpVc,
            ClaimFormatPayload::ProofTypeValues(vec!["ecdsa-rdfc-2019".into()]),
        );

        // Insert support for JWT VC format.
        // Per OID4VP v1.0 Section B.1.3.1.1, jwt_vc_json covers both credentials and presentations.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::JwtVcJson,
            ClaimFormatPayload::AlgValues(vec!["ES256".into()]),
        );

        metadata
            // Insert support for client ID prefixes.
            .add_client_id_prefixes_supported(&[
                ClientIdScheme(ClientIdScheme::DECENTRALIZED_IDENTIFIER.to_string()),
                ClientIdScheme(ClientIdScheme::REDIRECT_URI.to_string()),
                ClientIdScheme(ClientIdScheme::X509_SAN_DNS.to_string()),
                ClientIdScheme(ClientIdScheme::X509_HASH.to_string()),
            ])
            .map_err(|e| OID4VPError::MetadataInitialization(format!("{e:?}")))?;

        metadata
            // Allow unencoded requests and ES256-signed requests (for x509_san_dns).
            .add_request_object_signing_alg_values_supported(ssi::jwk::Algorithm::None)
            .map_err(|e| OID4VPError::MetadataInitialization(format!("{e:?}")))?;

        metadata
            .add_request_object_signing_alg_values_supported(ssi::jwk::Algorithm::ES256)
            .map_err(|e| OID4VPError::MetadataInitialization(format!("{e:?}")))?;

        Ok(metadata)
    }

    /// This will return all the credentials that match the DCQL query.
    async fn search_credentials_vs_dcql_query(
        &self,
        dcql_query: &DcqlQuery,
    ) -> Result<Vec<(String, Arc<ParsedCredential>)>, OID4VPError> {
        let all_credentials = match &self.provided_credentials {
            // Use a pre-selected list of credentials if provided.
            Some(credentials) => credentials.to_owned(),
            None => match &self.vdc_collection {
                None => vec![],
                Some(vdc_collection) => {
                    futures::stream::iter(vdc_collection.all_entries().await?.into_iter())
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
        };

        // Match credentials against each credential query in the DCQL query
        let mut matched_credentials: Vec<(String, Arc<ParsedCredential>)> = Vec::new();

        for cred_query in dcql_query.credentials() {
            for cred in &all_credentials {
                if cred.satisfies_dcql_query(cred_query) {
                    matched_credentials.push((cred_query.id().to_string(), cred.clone()));
                }
            }
        }

        Ok(matched_credentials)
    }

    // Internal method for returning the `PermissionRequest` for an oid4vp request.
    async fn permission_request(
        &self,
        request: AuthorizationRequestObject,
    ) -> Result<Arc<PermissionRequest>, OID4VPError> {
        // Resolve the DCQL query from the request.
        let dcql_query = request
            .dcql_query()
            .ok_or_else(|| {
                OID4VPError::DcqlQueryResolution(
                    "request object does not contain a dcql_query".into(),
                )
            })?
            .map_err(|e| OID4VPError::DcqlQueryResolution(format!("{e:?}")))?;

        let matched_credentials = self.search_credentials_vs_dcql_query(&dcql_query).await?;

        if matched_credentials.is_empty() {
            return Err(OID4VPError::PermissionRequest(
                PermissionRequestError::NoCredentialsFound,
            ));
        }

        let credentials = matched_credentials
            .into_iter()
            .map(|(credential_query_id, c)| {
                Arc::new(PresentableCredential {
                    inner: c.inner.clone(),
                    selected_fields: None,
                    credential_query_id,
                })
            })
            .collect::<Vec<_>>();

        Ok(PermissionRequest::new(
            dcql_query,
            credentials,
            request,
            self.signer.clone(),
            self.context_map.clone(),
            self.keystore.clone(),
        ))
    }
}

#[async_trait::async_trait]
impl RequestVerifier for Holder {
    /// Performs verification on Authorization Request Objects
    /// when `client_id_scheme` is `decentralized_identifier`.
    async fn decentralized_identifier(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying decentralized_identifier request.");

        let request_jwt = request_jwt
            .context("request JWT is required for decentralized_identifier verification")?;

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

        let request_jwt =
            request_jwt.context("request JWT is required for redirect_uri verification")?;

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

    /// Performs verification on Authorization Request Objects when `client_id_scheme` is `x509_san_dns`.
    async fn x509_san_dns(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying x509_san_dns request.");

        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_dns verification")?;

        // Use the x509_san validation with P256 verifier
        // Note: trusted_roots is None for now, meaning we don't verify the certificate chain
        x509_san::validate::<P256Verifier>(&self.metadata, decoded_request, request_jwt, None)?;

        Ok(())
    }

    /// Performs verification on Authorization Request Objects when `client_id_scheme` is `x509_hash`.
    async fn x509_hash(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying x509_hash request.");

        let request_jwt =
            request_jwt.context("request JWT is required for x509_hash verification")?;

        // Use the x509_hash validation with P256 verifier
        // Note: trusted_roots is None for now, meaning we don't verify the certificate chain
        x509_hash::validate::<P256Verifier>(&self.metadata, decoded_request, request_jwt, None)?;

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
pub(crate) mod tests {
    use super::*;
    use crate::{
        context::default_ld_json_context,
        did::DidMethod,
        oid4vp::presentation::{PresentationError, PresentationSigner},
        tests::{load_jwk, load_signer},
    };

    use json_vc::JsonVc;
    use jwt_vc::JwtVc;
    use ssi::{
        claims::{data_integrity::CryptosuiteString, jws::JwsSigner},
        crypto::Algorithm,
        JWK,
    };
    use vcdm2_sd_jwt::VCDM2SdJwt;

    #[derive(Debug)]
    pub(crate) struct KeySigner {
        pub(crate) jwk: JWK,
    }

    #[async_trait::async_trait]
    impl PresentationSigner for KeySigner {
        async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, PresentationError> {
            let sig = self
                .jwk
                .sign_bytes(&payload)
                .await
                .expect("failed to sign Jws Payload");

            // Convert signature bytes to DER encoded signature.
            p256::ecdsa::Signature::from_slice(&sig)
                .map(|sig| sig.to_der().as_bytes().to_vec())
                .map_err(|e| PresentationError::Signing(format!("{e:?}")))
        }

        fn algorithm(&self) -> Algorithm {
            self.jwk
                .algorithm
                .map(Algorithm::from)
                .unwrap_or(Algorithm::ES256)
        }

        async fn verification_method(&self) -> String {
            DidMethod::Key
                .vm_from_jwk(&self.jwk())
                .await
                // SAFETY: The JWK should always be well-formed and this method should not panic.
                .unwrap()
                .id
                .to_string()
        }

        fn did(&self) -> String {
            DidMethod::Key
                .did_from_jwk(&self.jwk())
                // SAFETY: The JWK should always be well-formed and this method should not panic.
                .unwrap()
                .to_string()
        }

        fn cryptosuite(&self) -> CryptosuiteString {
            CryptosuiteString::new("ecdsa-rdfc-2019".to_string()).unwrap()
        }

        fn jwk(&self) -> String {
            serde_json::to_string(&self.jwk.to_public()).unwrap()
        }
    }

    // NOTE: This test requires the `companion` service to be running and
    // available at localhost:3000.
    //
    // See: https://github.com/spruceid/companion/pull/1
    #[ignore]
    #[tokio::test]
    async fn test_companion_sd_jwt() -> Result<(), Box<dyn std::error::Error>> {
        let example_sd_jwt = include_str!("../../tests/examples/sd_vc.jwt");
        let sd_jwt = VCDM2SdJwt::new_from_compact_sd_jwt(example_sd_jwt.into())?;
        let credential = ParsedCredential::new_sd_jwt(sd_jwt);

        let jwk = JWK::generate_p256();
        let key_signer = KeySigner { jwk };
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

        // Make a request to the OID4VP URL.
        let holder = Holder::new_with_credentials(
            vec![credential.clone()],
            vec!["did:web:localhost%3A3000:oid4vp:client".into()],
            Box::new(key_signer),
            None,
            None,
        )
        .await?;

        let permission_request = holder.authorization_request(AuthRequest::Url(url)).await?;

        let parsed_credentials = permission_request.credentials();

        assert_eq!(parsed_credentials.len(), 1);

        for credential in parsed_credentials.iter() {
            let requested_fields = permission_request.requested_fields(credential);

            assert!(!requested_fields.is_empty());
        }

        // Get the first credential query ID from the DCQL query
        let credential_query_id = permission_request
            .dcql_query()
            .credentials()
            .first()
            .map(|c: &openid4vp::core::dcql_query::DcqlCredentialQuery| c.id().to_string())
            .unwrap_or_default();

        // NOTE: passing `parsed_credentials` as `selected_credentials`.
        let response = permission_request
            .create_permission_response(
                parsed_credentials,
                vec![credential
                    .requested_fields_dcql(permission_request.dcql_query(), &credential_query_id)
                    .iter()
                    .map(|rf| rf.path())
                    .collect()],
                ResponseOptions::default(),
            )
            .await?;

        holder.submit_permission_response(response).await?;

        Ok(())
    }

    #[ignore]
    #[tokio::test]
    async fn test_vc_playground_presentation() -> Result<(), Box<dyn std::error::Error>> {
        let jwk = JWK::generate_p256();

        let key_signer = KeySigner { jwk };

        let auth_url: Url = "openid4vp://?client_id=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz19p8m2tSznggCCT5ksDpGgZF%2Fopenid%2Fclient%2Fauthorization%2Fresponse&request_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz19p8m2tSznggCCT5ksDpGgZF%2Fopenid%2Fclient%2Fauthorization%2Frequest".parse().expect("Failed to parse auth URL.");

        let json_vc = JsonVc::new_from_json(
            include_str!("../../tests/examples/employment_authorization_document_vc.json").into(),
        )
        .expect("failed to create JSON VC credential");

        let credential = ParsedCredential::new_ldp_vc(json_vc);

        let mut context = HashMap::new();

        context.insert(
            "https://w3id.org/citizenship/v4rc1".into(),
            include_str!("../../tests/context/w3id_org_citizenship_v4rc1.json").into(),
        );
        context.insert(
            "https://w3id.org/vc/render-method/v2rc1".into(),
            include_str!("../../tests/context/w3id_org_vc_render_method_v2rc1.json").into(),
        );

        let holder = Holder::new_with_credentials(
            vec![credential.clone()],
            vec![],
            Box::new(key_signer),
            Some(context),
            None,
        )
        .await
        .expect("Failed to create oid4vp holder");

        let permission_request = holder
            .authorization_request(AuthRequest::Url(auth_url))
            .await
            .expect("Failed to authorize request URL");

        let credentials = permission_request.credentials();

        // Get the first credential query ID from the DCQL query
        let credential_query_id = permission_request
            .dcql_query()
            .credentials()
            .first()
            .map(|c: &openid4vp::core::dcql_query::DcqlCredentialQuery| c.id().to_string())
            .unwrap_or_default();

        let response = permission_request
            .create_permission_response(
                credentials,
                vec![credential
                    .requested_fields_dcql(permission_request.dcql_query(), &credential_query_id)
                    .iter()
                    .map(|rf| rf.path())
                    .collect()],
                ResponseOptions::default(),
            )
            .await
            .expect("failed to create permission response");

        let _url = holder.submit_permission_response(response).await?;

        Ok(())
    }

    #[ignore]
    #[tokio::test]
    async fn test_mdl_jwt() -> Result<(), Box<dyn std::error::Error>> {
        let auth_url = "openid4vp://?client_id=did%3Aweb%3Aqa.opencred.org&request_uri=https%3A%2F%2Fqa.opencred.org%2Fworkflows%2Fz19mLsUzMweuIUlk349cpekAz%2Fexchanges%2Fz19wpZZ49a2hnKdVd4uxgxPnC%2Fopenid%2Fclient%2Fauthorization%2Frequest";

        let key_signer = KeySigner { jwk: load_jwk() };

        let mdl = ParsedCredential::new_jwt_vc_json_ld(
            JwtVc::new_from_compact_jws(include_str!("../../tests/examples/mdl.jwt").into())
                .expect("failed to create mDL Jwt VC"),
        );

        let holder = Holder::new_with_credentials(
            vec![mdl],
            vec![],
            Box::new(key_signer),
            Some(default_ld_json_context()),
            None,
        )
        .await?;

        let permission_request = holder
            .authorization_request(AuthRequest::Url(
                auth_url.parse().expect("failed to parse url"),
            ))
            .await?;

        println!(
            "DCQL Query: {}",
            serde_json::to_string_pretty(&permission_request.dcql_query())
                .expect("failed to serialize DCQL query")
        );

        let credentials = permission_request.credentials();

        let requested_fields = credentials
            .iter()
            .map(|c| {
                permission_request
                    .requested_fields(c)
                    .iter()
                    .map(|f| f.path())
                    .collect::<Vec<_>>()
            })
            .collect::<Vec<_>>();

        let response = permission_request
            .create_permission_response(credentials, requested_fields, ResponseOptions::default())
            .await
            .expect("failed to create permission response");

        holder.submit_permission_response(response).await?;

        Ok(())
    }

    // NOTE: This test requires the `companion` service to be running and
    // available at localhost:3000.
    //
    // See: https://github.com/spruceid/companion/pull/1
    #[ignore]
    #[tokio::test]
    async fn test_companion_json_ld_vcdm_1() -> Result<(), Box<dyn std::error::Error>> {
        let alumni_vc = include_str!("../../tests/examples/alumni_vc.json");
        let json_vc = JsonVc::new_from_json(alumni_vc.into())?;

        let credential = ParsedCredential::new_ldp_vc(json_vc);

        let jwk = JWK::generate_p256();
        let key_signer = KeySigner { jwk };
        let initiate_api = "http://localhost:3000/api/oid4vp/initiate";

        // Make a request to the OID4VP initiate API.
        // provide a url-encoded `format` parameter to specify the format of the presentation.
        let response: (String, String) = reqwest::Client::new()
            .post(initiate_api)
            .form(&[("format", "json_ld")])
            .send()
            .await?
            .json()
            .await?;

        let _id = response.0;
        let url = Url::parse(&response.1).expect("failed to parse url");

        // Make a request to the OID4VP URL.
        let holder = Holder::new_with_credentials(
            vec![credential.clone()],
            vec!["did:web:localhost%3A3000:oid4vp:client".into()],
            Box::new(key_signer),
            None,
            None,
        )
        .await?;

        let permission_request = holder.authorization_request(AuthRequest::Url(url)).await?;

        let parsed_credentials = permission_request.credentials();

        assert_eq!(parsed_credentials.len(), 1);

        for credential in parsed_credentials.iter() {
            let requested_fields = permission_request.requested_fields(credential);

            assert!(!requested_fields.is_empty());
        }

        // Get the first credential query ID from the DCQL query
        let credential_query_id = permission_request
            .dcql_query()
            .credentials()
            .first()
            .map(|c: &openid4vp::core::dcql_query::DcqlCredentialQuery| c.id().to_string())
            .unwrap_or_default();

        // NOTE: passing `parsed_credentials` as `selected_credentials`.
        let response = permission_request
            .create_permission_response(
                parsed_credentials,
                vec![credential
                    .requested_fields_dcql(permission_request.dcql_query(), &credential_query_id)
                    .iter()
                    .map(|rf| rf.path())
                    .collect()],
                ResponseOptions::default(),
            )
            .await?;

        holder.submit_permission_response(response).await?;

        Ok(())
    }

    // NOTE: This test requires the `companion` service to be running and
    // available at localhost:3000.
    //
    // See: https://github.com/spruceid/companion/pull/1
    #[ignore]
    #[tokio::test]
    async fn test_companion_json_ld_vcdm_2() -> Result<(), Box<dyn std::error::Error>> {
        let signer = load_signer();

        let employment_auth_doc =
            include_str!("../../tests/examples/employment_authorization_document_vc.json");
        let json_vc = JsonVc::new_from_json(employment_auth_doc.into())?;

        let credential = ParsedCredential::new_ldp_vc(json_vc);
        let initiate_api = "http://localhost:3000/api/oid4vp/initiate";

        // Make a request to the OID4VP initiate API.
        // provide a url-encoded `format` parameter to specify the format of the presentation.
        let response: (String, String) = reqwest::Client::new()
            .post(initiate_api)
            .form(&[("format", "json_ld")])
            .send()
            .await?
            .json()
            .await?;

        let _id = response.0;
        let url = Url::parse(&response.1).expect("failed to parse url");

        // Make a request to the OID4VP URL.
        let holder = Holder::new_with_credentials(
            vec![credential.clone()],
            vec!["did:web:localhost%3A3000:oid4vp:client".into()],
            Box::new(signer),
            Some(default_ld_json_context()),
            None,
        )
        .await?;

        let permission_request = holder.authorization_request(AuthRequest::Url(url)).await?;

        let parsed_credentials = permission_request.credentials();

        assert_eq!(parsed_credentials.len(), 1);

        for credential in parsed_credentials.iter() {
            let requested_fields = permission_request.requested_fields(credential);

            assert!(!requested_fields.is_empty());
        }

        // Get the first credential query ID from the DCQL query
        let credential_query_id = permission_request
            .dcql_query()
            .credentials()
            .first()
            .map(|c: &openid4vp::core::dcql_query::DcqlCredentialQuery| c.id().to_string())
            .unwrap_or_default();

        // NOTE: passing `parsed_credentials` as `selected_credentials`.
        let response = permission_request
            .create_permission_response(
                parsed_credentials,
                vec![credential
                    .requested_fields_dcql(permission_request.dcql_query(), &credential_query_id)
                    .iter()
                    .map(|rf| rf.path())
                    .collect()],
                ResponseOptions::default(),
            )
            .await?;

        holder.submit_permission_response(response).await?;

        Ok(())
    }
}
