#![allow(deprecated)]

use super::client_auth;
use super::dynamic_credential::{DynamicCredentialOffer, DynamicCredentialProvider};
use super::error::OID4VPError;
use super::permission_request::*;
use super::presentation::PresentationSigner;
use super::type_values;
use crate::credential::*;
use crate::crypto::KeyStore;
use crate::http_client::HttpClient;
use crate::vdc_collection::VdcCollection;
use openid4vp::core::dcql_query::CredentialTypes;

use std::collections::{HashMap, HashSet};
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

use ssi::dids::DIDWeb;
use ssi::dids::VerificationMethodDIDResolver;
use ssi::prelude::AnyJwkMethod;
use uniffi::deps::anyhow;
use url::Url;

#[derive(serde::Serialize)]
struct AccessDeniedResponse {
    error: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    state: Option<String>,
}

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
    pub(crate) client: HttpClient,

    /// A list of trusted DIDs.
    pub(crate) trusted_dids: Vec<String>,

    /// Provide optional credentials to the holder instance.
    pub(crate) provided_credentials: Option<Vec<Arc<ParsedCredential>>>,

    /// Foreign Interface for the [PresentationSigner]
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,

    /// Signing key id used for credentials with no `key_alias` (e.g. legacy
    /// credentials issued under the shared key).
    pub(crate) key_id: String,

    /// Optional context map for resolving specific contexts
    pub(crate) context_map: Option<HashMap<String, String>>,

    /// Optional KeyStore for mdoc credential signing
    pub(crate) keystore: Option<Arc<dyn KeyStore>>,

    /// Dynamic credential providers that can mint credentials on device during
    /// a presentation. Empty by default; see [`DynamicCredentialProvider`].
    pub(crate) providers: Vec<Arc<dyn DynamicCredentialProvider>>,
}

impl std::fmt::Debug for Holder {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Holder")
            .field("vdc_collection", &self.vdc_collection)
            .field("metadata", &self.metadata)
            .field("trusted_dids", &self.trusted_dids)
            .field("provided_credentials", &self.provided_credentials)
            .field("keystore", &self.keystore.as_ref().map(|_| "KeyStore"))
            .field("providers", &self.providers.len())
            .finish()
    }
}

/// Default the JSON-LD context map to the SDK's bundled contexts (alumni,
/// first-responder, citizenship, render-method, …) when the caller supplies
/// none or an empty map — mirroring `VcalmHolder::new_session`. Without this, an
/// LDP-VC whose `@context` references a bundled-only context (e.g. a
/// VCALM-issued FirstResponderCredential using `w3id.org/first-responder/v1`
/// and the render-method contexts) cannot be canonicalized when building the VP,
/// so presentation fails. An explicit non-empty map still overrides.
fn with_default_contexts(
    context_map: Option<HashMap<String, String>>,
) -> Option<HashMap<String, String>> {
    Some(match context_map {
        Some(map) if !map.is_empty() => map,
        _ => crate::context::default_ld_json_context(),
    })
}

#[uniffi::export(async_runtime = "tokio")]
impl Holder {
    /// Uses VDC collection to retrieve the credentials for a given presentation definition.
    #[uniffi::constructor]
    pub async fn new(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn PresentationSigner>,
        key_id: String,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, OID4VPError> {
        Self::new_with_providers(
            vdc_collection,
            trusted_dids,
            signer,
            key_id,
            context_map,
            keystore,
            vec![],
        )
        .await
    }

    /// Like [`Holder::new`], but additionally registers a set of
    /// [`DynamicCredentialProvider`]s that can mint credentials on device during
    /// a presentation.
    ///
    /// This is purely additive: passing an empty `providers` vec is equivalent
    /// to [`Holder::new`].
    #[uniffi::constructor]
    pub async fn new_with_providers(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn PresentationSigner>,
        key_id: String,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
        providers: Vec<Arc<dyn DynamicCredentialProvider>>,
    ) -> Result<Arc<Self>, OID4VPError> {
        let client = HttpClient::shared();

        Ok(Arc::new(Self {
            client,
            vdc_collection: Some(vdc_collection),
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: None,
            signer: Arc::new(signer),
            key_id,
            context_map: with_default_contexts(context_map),
            keystore,
            providers,
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
        key_id: String,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, OID4VPError> {
        Self::new_with_credentials_and_providers(
            provided_credentials,
            trusted_dids,
            signer,
            key_id,
            context_map,
            keystore,
            vec![],
        )
        .await
    }

    /// Like [`Holder::new_with_credentials`], but additionally registers a set
    /// of [`DynamicCredentialProvider`]s that can mint credentials on device
    /// during a presentation.
    ///
    /// This is purely additive: passing an empty `providers` vec is equivalent
    /// to [`Holder::new_with_credentials`].
    #[uniffi::constructor]
    pub async fn new_with_credentials_and_providers(
        provided_credentials: Vec<Arc<ParsedCredential>>,
        trusted_dids: Vec<String>,
        signer: Box<dyn PresentationSigner>,
        key_id: String,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
        providers: Vec<Arc<dyn DynamicCredentialProvider>>,
    ) -> Result<Arc<Self>, OID4VPError> {
        let client = HttpClient::shared();

        Ok(Arc::new(Self {
            client,
            vdc_collection: None,
            metadata: Self::metadata()?,
            trusted_dids,
            provided_credentials: Some(provided_credentials),
            signer: Arc::new(signer),
            key_id,
            context_map: with_default_contexts(context_map),
            keystore,
            providers,
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

    pub async fn deny_permission(
        &self,
        request: Arc<PermissionRequest>,
    ) -> Result<Option<Url>, OID4VPError> {
        self.submit_permission_denial(&request.request).await
    }
}

// Internal methods for the Holder.
impl Holder {
    /// Notify the verifier that the end-user declined the authorization request.
    ///
    /// This sends the OID4VP `access_denied` authorization error and echoes the
    /// request's `state`, when present. It intentionally omits an
    /// `error_description` so declining a request discloses no additional
    /// wallet or credential information.
    ///
    /// OID4VP permits an unencrypted error response for `direct_post.jwt`, so
    /// this response is form-encoded for both direct-post response modes.
    pub(crate) async fn submit_permission_denial(
        &self,
        request: &AuthorizationRequestObject,
    ) -> Result<Option<Url>, OID4VPError> {
        match request.response_mode() {
            ResponseMode::DirectPost | ResponseMode::DirectPostJwt => {}
            mode => return Err(OID4VPError::UnsupportedResponseMode(mode.to_string())),
        }

        let state = request
            .state()
            .transpose()
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?
            .map(|state| state.0);

        let body = serde_urlencoded::to_string(AccessDeniedResponse {
            error: "access_denied",
            state,
        })
        .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?;
        let http_request = http::Request::post(request.return_uri().as_str())
            .header("Prefer", "OID4VP-1.0.0")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(body.into_bytes())
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?;
        let response = self
            .client
            .send(http_request)
            .await
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?;

        let status = response.status();
        let body = String::from_utf8(response.into_body())
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?;

        if !status.is_success() {
            return Err(OID4VPError::ResponseSubmission(format!(
                "verifier rejected authorization error (status: {status}): {body}"
            )));
        }

        if body.trim().is_empty() {
            return Ok(None);
        }

        serde_json::from_str::<serde_json::Value>(&body)
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?
            .get("redirect_uri")
            .and_then(|value| value.as_str())
            .map(Url::parse)
            .transpose()
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))
    }

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

        // Insert support for IETF SD-JWT VC (dc+sd-jwt) format.
        // Per draft-ietf-oauth-sd-jwt-vc-14.
        metadata.vp_formats_supported_mut().0.insert(
            ClaimFormatDesignation::DcSdJwt,
            ClaimFormatPayload::Other(serde_json::json!({
                "sd-jwt_alg_values": ["ES256"],
                "kb-jwt_alg_values": ["ES256"]
            })),
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

        // `request_object_signing_alg_values_supported` is ES256 only, from
        // the static metadata. `none` is not a signing algorithm: unsigned
        // requests are only accepted for the `redirect_uri` prefix, which
        // `Holder::redirect_uri` checks itself, and listing it let
        // `decentralized_identifier` requests skip their signature check.

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
        };

        // Expand W3C VC types once, only if a query uses `type_values`
        // (OID4VP 1.0 Appendix B.1.1).
        let expanded_types = if dcql_query.requests_type_values() {
            type_values::expand_all(&all_credentials, self.context_map.clone()).await?
        } else {
            vec![CredentialTypes::NotApplicable; all_credentials.len()]
        };

        // Match credentials against each credential query in the DCQL query
        let mut matched_credentials: Vec<(String, Arc<ParsedCredential>)> = Vec::new();

        for cred_query in dcql_query.credentials() {
            for (cred, types) in all_credentials.iter().zip(&expanded_types) {
                if cred.satisfies_dcql_query(cred_query) && cred_query.satisfies_type_values(types)
                {
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
        // TODO(transaction_data): not supported yet, so the parameter is
        // ignored, while OID4VP 1.0 §8.4 requires an error then. Start here;
        // see §5.1, §8.4, Appendix B.2.1 (mdoc) and B.3.3 (SD-JWT). The
        // `openid4vp` crate already parses it (`parameters::TransactionData`).

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

        // Stored credentials that matched the DCQL query.
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

        // Dynamic offers: for each DCQL credential query, ask every provider
        // what it could mint, and surface those offers via a parallel accessor
        // (`PermissionRequest::dynamic_offers`) — separate from the stored-only
        // `credentials()`. Each offer is mapped back to its owning provider by
        // `offer_id` so the response step can call back into `mint`.
        let mut dynamic_offers: Vec<DynamicCredentialOffer> = Vec::new();
        let mut offer_providers: HashMap<String, Arc<dyn DynamicCredentialProvider>> =
            HashMap::new();
        for provider in &self.providers {
            for cred_query in dcql_query.credentials() {
                let query_json =
                    crate::oid4vp::dynamic_credential::DcqlCredentialQueryJson::from_query(
                        cred_query,
                    );
                for offer in provider.offers(query_json).await {
                    offer_providers.insert(offer.offer_id.clone(), provider.clone());
                    dynamic_offers.push(offer);
                }
            }
        }

        let matched_query_ids: HashSet<&str> = credentials
            .iter()
            .map(|c| c.credential_query_id.as_str())
            .chain(
                dynamic_offers
                    .iter()
                    .map(|o| o.credential_query_id.as_str()),
            )
            .collect();
        if matched_query_ids.is_empty() || !is_satisfiable(&dcql_query, &matched_query_ids) {
            return Err(OID4VPError::PermissionRequest(
                PermissionRequestError::NoCredentialsFound,
            ));
        }

        Ok(PermissionRequest::new_with_offers(
            dcql_query,
            credentials,
            request,
            self.signer.clone(),
            self.key_id.clone(),
            self.context_map.clone(),
            self.keystore.clone(),
            dynamic_offers,
            offer_providers,
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

        // "The request MUST be signed with a private key associated with the
        // DID" (OID4VP 1.0 §5.9.3). The `openid4vp` crate skips every check
        // for `alg: none`, so refuse it before calling it.
        client_auth::ensure_signed(&request_jwt)?;

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
    ///
    /// The request is unsigned (OID4VP 1.0 §5.9.3), as plain parameters or as
    /// an `alg: none` Request Object, and its Response URI must be the Client
    /// Identifier.
    async fn redirect_uri(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> anyhow::Result<()> {
        log::debug!("Verifying redirect_uri request.");

        client_auth::verify_redirect_uri(decoded_request, request_jwt.as_deref())
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

        client_auth::ensure_signed(&request_jwt)?;

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

        client_auth::ensure_signed(&request_jwt)?;

        // Use the x509_hash validation with P256 verifier
        // Note: trusted_roots is None for now, meaning we don't verify the certificate chain
        x509_hash::validate::<P256Verifier>(&self.metadata, decoded_request, request_jwt, None)?;

        Ok(())
    }
}

impl OID4VPWallet for Holder {
    type HttpClient = HttpClient;

    fn http_client(&self) -> &Self::HttpClient {
        &self.client
    }

    fn metadata(&self) -> &WalletMetadata {
        &self.metadata
    }
}

pub(crate) fn is_satisfiable(dcql_query: &DcqlQuery, matched_query_ids: &HashSet<&str>) -> bool {
    let all_matched = |ids: &[String]| ids.iter().all(|id| matched_query_ids.contains(id.as_str()));
    match dcql_query.credential_sets() {
        None => dcql_query
            .credentials()
            .iter()
            .all(|query| matched_query_ids.contains(query.id())),
        Some(sets) => sets
            .iter()
            .filter(|set| set.is_required())
            .all(|set| set.options().iter().any(|option| all_matched(option))),
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
        // The test signer holds a single key, so `key_id` is ignored.
        async fn sign(
            &self,
            _key_id: String,
            payload: Vec<u8>,
        ) -> Result<Vec<u8>, PresentationError> {
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

        async fn verification_method(&self, _key_id: String) -> String {
            DidMethod::Key
                .vm_from_jwk(&self.public_jwk_string())
                .await
                // SAFETY: The JWK should always be well-formed and this method should not panic.
                .unwrap()
                .id
                .to_string()
        }

        fn did(&self, _key_id: String) -> String {
            DidMethod::Key
                .did_from_jwk(&self.public_jwk_string())
                // SAFETY: The JWK should always be well-formed and this method should not panic.
                .unwrap()
                .to_string()
        }

        fn cryptosuite(&self) -> CryptosuiteString {
            CryptosuiteString::new("ecdsa-rdfc-2019".to_string()).unwrap()
        }

        fn jwk(&self, _key_id: String) -> String {
            self.public_jwk_string()
        }
    }

    impl KeySigner {
        fn public_jwk_string(&self) -> String {
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
            String::new(),
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
            String::new(),
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
            String::new(),
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
            String::new(),
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
            String::new(),
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

    // ---- DCQL `meta.type_values` ----

    /// OID4VP 1.0 Appendix B.1.1: `type_values` matches expanded or written
    /// types.
    #[tokio::test]
    async fn dcql_type_values_match_expanded_or_written_types() {
        let alumni =
            JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into())
                .expect("failed to parse alumni credential");

        let holder = Holder::new_with_credentials(
            vec![ParsedCredential::new_ldp_vc(alumni)],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            None,
            None,
        )
        .await
        .expect("failed to construct holder");

        let search = |type_values: serde_json::Value| {
            let query: DcqlQuery = serde_json::from_value(serde_json::json!({
                "credentials": [{
                    "id": "alumni",
                    "format": "ldp_vc",
                    "meta": { "type_values": type_values }
                }]
            }))
            .expect("failed to parse DCQL query");
            let holder = holder.clone();
            async move {
                holder
                    .search_credentials_vs_dcql_query(&query)
                    .await
                    .expect("failed to search credentials")
                    .len()
            }
        };

        // The alumni credential's inline `@context` defines `AlumniCredential`.
        let expanded = serde_json::json!([[
            "https://www.w3.org/2018/credentials#VerifiableCredential",
            "https://examples.vcplayground.org/contexts/alumni/vocab#AlumniCredential"
        ]]);
        assert_eq!(search(expanded).await, 1);

        // The terms as written also match, for interoperability.
        let terms = serde_json::json!([["VerifiableCredential", "AlumniCredential"]]);
        assert_eq!(search(terms).await, 1);

        // A type the credential does not have.
        let other =
            serde_json::json!([["https://example.org/examples#UniversityDegreeCredential"]]);
        assert_eq!(search(other).await, 0);
    }

    #[tokio::test]
    async fn dcql_type_values_match_a_jwt_vc_by_its_vc_claim() {
        let vc = JwtVc::new_from_compact_jws(include_str!("../../tests/examples/mdl.jwt").into())
            .expect("failed to parse JWT VC");

        let holder = Holder::new_with_credentials(
            vec![ParsedCredential::new_jwt_vc_json(vc)],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            None,
            None,
        )
        .await
        .expect("failed to construct holder");

        let search = |type_values: serde_json::Value| {
            let query: DcqlQuery = serde_json::from_value(serde_json::json!({
                "credentials": [{
                    "id": "mdl",
                    "format": "jwt_vc_json",
                    "meta": { "type_values": type_values }
                }]
            }))
            .expect("failed to parse DCQL query");
            let holder = holder.clone();
            async move {
                holder
                    .search_credentials_vs_dcql_query(&query)
                    .await
                    .expect("failed to search credentials")
                    .len()
            }
        };

        let written =
            serde_json::json!([["VerifiableCredential", "Iso18013DriversLicenseCredential"]]);
        assert_eq!(search(written).await, 1);

        let other = serde_json::json!([["VerifiableCredential", "AlumniCredential"]]);
        assert_eq!(search(other).await, 0);
    }

    // ---- DCQL `require_cryptographic_holder_binding` ----

    const IDENTITY_VCT: &str = "https://credentials.example/identity";

    /// An SD-JWT VC with a selectively disclosable `given_name`, and a `cnf`
    /// claim when `cnf` is set.
    async fn identity_sd_jwt_vc(cnf: bool) -> Arc<ietf_sd_jwt_vc::IetfSdJwtVc> {
        use ssi::claims::{jwt::JWTClaims, sd_jwt::ConcealJwtClaims, sd_jwt::SdAlg};

        let mut claims = serde_json::json!({
            "iss": "https://issuer.example",
            "vct": IDENTITY_VCT,
            "given_name": "Arthur"
        });
        if cnf {
            claims["cnf"] = serde_json::json!({ "jwk": JWK::generate_p256().to_public() });
        }
        let claims: JWTClaims = serde_json::from_value(claims).expect("invalid JWT claims");

        let sd_jwt = claims
            .conceal_and_sign(
                SdAlg::Sha256,
                &[ssi::JsonPointerBuf::new("/given_name".to_owned()).unwrap()],
                &JWK::generate_p256(),
            )
            .await
            .expect("failed to sign SD-JWT");

        ietf_sd_jwt_vc::IetfSdJwtVc::new_from_compact_sd_jwt(sd_jwt.to_string())
            .expect("failed to parse SD-JWT VC")
    }

    /// The `vp_token` item for an identity query, or `None` without a match.
    async fn present_identity(
        credential: Arc<ietf_sd_jwt_vc::IetfSdJwtVc>,
        require_holder_binding: bool,
    ) -> Option<String> {
        let request: AuthorizationRequestObject = serde_json::from_value(serde_json::json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-kb",
            "nonce": "nonce-kb",
            "dcql_query": {
                "credentials": [{
                    "id": "identity",
                    "format": "dc+sd-jwt",
                    "meta": { "vct_values": [IDENTITY_VCT] },
                    "require_cryptographic_holder_binding": require_holder_binding,
                    "claims": [{ "path": ["given_name"] }]
                }]
            }
        }))
        .expect("failed to parse request");

        let holder = Holder::new_with_credentials(
            vec![ParsedCredential::new_dc_sd_jwt(credential)],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            None,
            None,
        )
        .await
        .expect("failed to construct holder");

        let permission_request = match holder
            .authorization_request(AuthRequest::Request(Box::new(request)))
            .await
        {
            Ok(permission_request) => permission_request,
            Err(OID4VPError::PermissionRequest(PermissionRequestError::NoCredentialsFound)) => {
                return None
            }
            Err(e) => panic!("failed to build permission request: {e}"),
        };

        let credentials = permission_request.credentials();
        let fields = credentials
            .iter()
            .map(|credential| {
                permission_request
                    .requested_fields(credential)
                    .iter()
                    .map(|field| field.path())
                    .collect()
            })
            .collect();

        let response = permission_request
            .create_permission_response(credentials, fields, ResponseOptions::default())
            .await
            .expect("failed to create permission response");

        let vp_token: serde_json::Value =
            serde_json::from_str(&response.vp_token().unwrap()).unwrap();
        Some(vp_token["identity"][0].as_str().unwrap().to_owned())
    }

    /// Whether a compact SD-JWT carries a KB-JWT (does not end with `~`).
    fn has_kb_jwt(sd_jwt: &str) -> bool {
        !sd_jwt.ends_with('~')
    }

    /// OID4VP 1.0 Appendix B.3.
    #[tokio::test]
    async fn dc_sd_jwt_holder_binding() {
        // Required (the default): SD-JWT+KB, and only with `cnf`.
        let bound = present_identity(identity_sd_jwt_vc(true).await, true).await;
        assert!(has_kb_jwt(&bound.expect("the credential has `cnf`")));
        assert_eq!(
            present_identity(identity_sd_jwt_vc(false).await, true).await,
            None
        );

        // Not required: an SD-JWT without `cnf` is returned without KB-JWT.
        let unbound = present_identity(identity_sd_jwt_vc(false).await, false).await;
        assert!(!has_kb_jwt(
            &unbound.expect("no holder binding is required")
        ));
    }

    // ---- DCQL query without `claims` ----

    async fn mdl_permission_request(claims: Option<serde_json::Value>) -> Arc<PermissionRequest> {
        use crate::crypto::{KeyAlias, RustTestKeyManager};

        let key_manager = Arc::new(RustTestKeyManager::default());
        let alias = KeyAlias("mdoc_without_claims".to_string());
        key_manager
            .generate_p256_signing_key(alias.clone())
            .await
            .unwrap();
        let mdoc = crate::mdl::util::generate_test_mdl(key_manager.clone(), alias).unwrap();

        let mut request = serde_json::json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-mdoc",
            "nonce": "nonce-mdoc",
            "dcql_query": {
                "credentials": [{
                    "id": "mdl",
                    "format": "mso_mdoc",
                    "meta": { "doctype_value": "org.iso.18013.5.1.mDL" }
                }]
            }
        });
        if let Some(claims) = claims {
            request["dcql_query"]["credentials"][0]["claims"] = claims;
        }
        let request: AuthorizationRequestObject =
            serde_json::from_value(request).expect("failed to parse request");

        let holder = Holder::new_with_credentials(
            vec![ParsedCredential::new_mso_mdoc(Arc::new(mdoc))],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            None,
            Some(key_manager),
        )
        .await
        .expect("failed to construct holder");

        holder
            .authorization_request(AuthRequest::Request(Box::new(request)))
            .await
            .expect("failed to build permission request")
    }

    /// OID4VP 1.0 §6.4.1: asked for no claims, an mdoc discloses no data
    /// element.
    #[tokio::test]
    async fn mdoc_without_claims_presents_no_data_element() {
        use base64::Engine as _;
        use isomdl::definitions::DeviceResponse;

        let permission_request = mdl_permission_request(None).await;
        let credentials = permission_request.credentials();
        assert!(permission_request
            .requested_fields(&credentials[0])
            .is_empty());

        let response = permission_request
            .create_permission_response(credentials, vec![vec![]], ResponseOptions::default())
            .await
            .expect("failed to create permission response");

        let vp_token: serde_json::Value =
            serde_json::from_str(&response.vp_token().unwrap()).unwrap();
        let device_response = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(vp_token["mdl"][0].as_str().unwrap())
            .unwrap();
        let device_response: DeviceResponse = isomdl::cbor::from_slice(&device_response).unwrap();

        let document = &device_response.documents.expect("one document")[0];
        assert_eq!(document.doc_type, "org.iso.18013.5.1.mDL");
        assert!(document.issuer_signed.namespaces.is_none());
    }

    #[tokio::test]
    async fn mdoc_with_claims_rejects_an_empty_selection() {
        let permission_request = mdl_permission_request(Some(serde_json::json!([
            { "path": ["org.iso.18013.5.1", "given_name"] }
        ])))
        .await;
        let credentials = permission_request.credentials();

        let error = permission_request
            .create_permission_response(credentials, vec![vec![]], ResponseOptions::default())
            .await
            .expect_err("an empty selection must not answer a query with claims");
        assert!(
            error.to_string().contains("No fields selected"),
            "unexpected error: {error}"
        );
    }

    async fn present_badge(
        require_holder_binding: bool,
        ldp_vc_formats: serde_json::Value,
    ) -> Result<serde_json::Value, OID4VPError> {
        let badge = JsonVc::new_from_json(
            include_str!("../../tests/examples/open_badge_rdfc_only_vc.json").into(),
        )
        .expect("failed to parse badge");

        let request: AuthorizationRequestObject = serde_json::from_value(serde_json::json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-badge",
            "nonce": "nonce-badge",
            "client_metadata": {
                "vp_formats_supported": { "ldp_vc": ldp_vc_formats }
            },
            "dcql_query": {
                "credentials": [{
                    "id": "badge",
                    "format": "ldp_vc",
                    "meta": {
                        "type_values": [["VerifiableCredential", "OpenBadgeCredential"]]
                    },
                    "require_cryptographic_holder_binding": require_holder_binding
                }]
            }
        }))
        .expect("failed to parse request");

        let holder = Holder::new_with_credentials(
            vec![ParsedCredential::new_ldp_vc(badge)],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .expect("failed to construct holder");

        let permission_request = holder
            .authorization_request(AuthRequest::Request(Box::new(request)))
            .await
            .expect("failed to build permission request");
        let credentials = permission_request.credentials();

        let response = permission_request
            .create_permission_response(credentials, vec![vec![]], ResponseOptions::default())
            .await?;
        Ok(serde_json::from_str(&response.vp_token().unwrap()).unwrap())
    }

    #[tokio::test]
    async fn ldp_vc_without_holder_binding_skips_the_signer_suite_check() {
        let vp_token = present_badge(
            false,
            serde_json::json!({
                "proof_type_values": ["DataIntegrityProof"],
                "cryptosuite_values": ["ecdsa-rdfc-2019"]
            }),
        )
        .await
        .expect("the signer is not used without holder binding");
        let item = &vp_token["badge"][0];
        assert!(item["type"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!("OpenBadgeCredential")));
        assert!(item.get("verifiableCredential").is_none());

        let error = present_badge(
            true,
            serde_json::json!({ "proof_type_values": ["eddsa-rdfc-2022"] }),
        )
        .await
        .expect_err("the verifier does not accept the signer's ecdsa-rdfc-2019");
        assert!(
            error
                .to_string()
                .contains("Cryptographic Suite not supported"),
            "unexpected error: {error}"
        );
    }

    // ---- DCQL query mixing credential formats ----

    async fn mixed_format_permission_request(
        sd_jwt_count: usize,
        identity_multiple: bool,
    ) -> Arc<PermissionRequest> {
        mixed_format_permission_request_with_sets(sd_jwt_count, identity_multiple, None).await
    }

    async fn mixed_format_permission_request_with_sets(
        sd_jwt_count: usize,
        identity_multiple: bool,
        credential_sets: Option<serde_json::Value>,
    ) -> Arc<PermissionRequest> {
        use crate::crypto::{KeyAlias, RustTestKeyManager};

        let key_manager = Arc::new(RustTestKeyManager::default());
        let alias = KeyAlias("mixed_formats".to_string());
        key_manager
            .generate_p256_signing_key(alias.clone())
            .await
            .unwrap();
        let mdoc = crate::mdl::util::generate_test_mdl(key_manager.clone(), alias).unwrap();

        let alumni =
            JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into())
                .expect("failed to parse alumni credential");

        let mut credentials = vec![
            ParsedCredential::new_mso_mdoc(Arc::new(mdoc)),
            ParsedCredential::new_ldp_vc(alumni),
        ];
        for _ in 0..sd_jwt_count {
            credentials.push(ParsedCredential::new_dc_sd_jwt(
                identity_sd_jwt_vc(true).await,
            ));
        }

        let mut request = serde_json::json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-mixed",
            "nonce": "nonce-mixed",
            "client_metadata": {
                "vp_formats_supported": {
                    "ldp_vc": { "proof_type_values": ["ecdsa-rdfc-2019"] }
                }
            },
            "dcql_query": {
                "credentials": [
                    {
                        "id": "alumni",
                        "format": "ldp_vc",
                        "meta": {
                            "type_values": [["VerifiableCredential", "AlumniCredential"]]
                        },
                        "claims": [{ "path": ["credentialSubject", "alumniOf", "name"] }]
                    },
                    {
                        "id": "identity",
                        "format": "dc+sd-jwt",
                        "meta": { "vct_values": [IDENTITY_VCT] },
                        "multiple": identity_multiple,
                        "claims": [{ "path": ["given_name"] }]
                    },
                    {
                        "id": "mdl",
                        "format": "mso_mdoc",
                        "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                        "claims": [{ "path": ["org.iso.18013.5.1", "given_name"] }]
                    }
                ]
            }
        });
        if let Some(credential_sets) = credential_sets {
            request["dcql_query"]["credential_sets"] = credential_sets;
        }
        let request: AuthorizationRequestObject =
            serde_json::from_value(request).expect("failed to parse request");

        let holder = Holder::new_with_credentials(
            credentials,
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            Some(default_ld_json_context()),
            Some(key_manager),
        )
        .await
        .expect("failed to construct holder");

        holder
            .authorization_request(AuthRequest::Request(Box::new(request)))
            .await
            .expect("failed to build permission request")
    }

    fn query_formats(
        permission_request: &PermissionRequest,
    ) -> Vec<(String, Vec<CredentialFormat>)> {
        permission_request
            .credentials_grouped_by_query()
            .into_iter()
            .map(|group| {
                let formats = group
                    .credentials
                    .iter()
                    .map(|credential| credential.as_parsed_credential().format())
                    .collect();
                (group.credential_query_id, formats)
            })
            .collect()
    }

    async fn present_selected(
        permission_request: &PermissionRequest,
        credentials: Vec<Arc<PresentableCredential>>,
    ) -> Result<serde_json::Value, OID4VPError> {
        let fields = credentials
            .iter()
            .map(|credential| {
                permission_request
                    .requested_fields(credential)
                    .iter()
                    .map(|field| field.path())
                    .collect()
            })
            .collect();

        let response = permission_request
            .create_permission_response(credentials, fields, ResponseOptions::default())
            .await?;

        Ok(serde_json::from_str(&response.vp_token().unwrap()).unwrap())
    }

    #[tokio::test]
    async fn mixed_format_query_matches_each_credential_to_its_own_query() {
        let permission_request = mixed_format_permission_request(1, false).await;

        assert_eq!(
            query_formats(&permission_request),
            [
                ("alumni".to_string(), vec![CredentialFormat::LdpVc]),
                ("identity".to_string(), vec![CredentialFormat::DcSdJwt]),
                ("mdl".to_string(), vec![CredentialFormat::MsoMdoc]),
            ]
        );
        assert!(permission_request.is_multi_credential_selection());
        assert!(!permission_request.is_multi_credential_matching());
    }

    #[tokio::test]
    async fn mixed_format_query_presents_one_item_per_query() {
        use base64::Engine as _;
        use isomdl::definitions::DeviceResponse;

        let permission_request = mixed_format_permission_request(1, false).await;
        let vp_token = present_selected(&permission_request, permission_request.credentials())
            .await
            .expect("failed to create permission response");

        let mut queries: Vec<&String> = vp_token.as_object().unwrap().keys().collect();
        queries.sort();
        assert_eq!(queries, ["alumni", "identity", "mdl"]);

        let alumni = &vp_token["alumni"].as_array().unwrap();
        assert_eq!(alumni.len(), 1);
        assert!(alumni[0].to_string().contains("AlumniCredential"));

        let identity = vp_token["identity"].as_array().unwrap();
        assert_eq!(identity.len(), 1);
        assert!(has_kb_jwt(identity[0].as_str().unwrap()));

        let mdl = vp_token["mdl"].as_array().unwrap();
        assert_eq!(mdl.len(), 1);
        let device_response = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(mdl[0].as_str().unwrap())
            .unwrap();
        let device_response: DeviceResponse = isomdl::cbor::from_slice(&device_response).unwrap();
        let document = &device_response.documents.expect("one document")[0];
        assert_eq!(document.doc_type, "org.iso.18013.5.1.mDL");
    }

    #[tokio::test]
    async fn mixed_format_query_counts_multiple_per_query() {
        let permission_request = mixed_format_permission_request(2, false).await;
        assert_eq!(
            query_formats(&permission_request)[1],
            (
                "identity".to_string(),
                vec![CredentialFormat::DcSdJwt, CredentialFormat::DcSdJwt]
            )
        );
        assert!(permission_request.is_multi_credential_matching());

        let error = present_selected(&permission_request, permission_request.credentials())
            .await
            .expect_err("two credentials for `identity` without `multiple`")
            .to_string();
        assert!(error.contains("identity"), "{error}");
        assert!(error.contains("does not allow `multiple`"), "{error}");

        let one_per_query = permission_request
            .credentials_grouped_by_query()
            .into_iter()
            .map(|group| group.credentials[0].clone())
            .collect();
        let vp_token = present_selected(&permission_request, one_per_query)
            .await
            .expect("one credential per query is always allowed");
        assert_eq!(vp_token["identity"].as_array().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn mixed_format_query_presents_multiple_for_the_query_that_allows_it() {
        let permission_request = mixed_format_permission_request(2, true).await;
        let vp_token = present_selected(&permission_request, permission_request.credentials())
            .await
            .expect("`identity` allows `multiple`");

        assert_eq!(vp_token["alumni"].as_array().unwrap().len(), 1);
        assert_eq!(vp_token["identity"].as_array().unwrap().len(), 2);
        assert_eq!(vp_token["mdl"].as_array().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn mixed_format_credential_sets_offer_alternative_formats() {
        let permission_request = mixed_format_permission_request_with_sets(
            1,
            false,
            Some(serde_json::json!([
                { "options": [["mdl"], ["identity"]] },
                { "options": [["alumni"]], "required": false }
            ])),
        )
        .await;

        let requirements: Vec<_> = permission_request
            .credential_requirements()
            .into_iter()
            .map(|requirement| {
                let formats: Vec<CredentialFormat> = requirement
                    .credentials
                    .iter()
                    .map(|credential| credential.as_parsed_credential().format())
                    .collect();
                (
                    requirement.credential_query_ids,
                    requirement.required,
                    formats,
                )
            })
            .collect();
        assert_eq!(
            requirements,
            [
                (
                    vec!["mdl".to_string(), "identity".to_string()],
                    true,
                    vec![CredentialFormat::MsoMdoc, CredentialFormat::DcSdJwt]
                ),
                (
                    vec!["alumni".to_string()],
                    false,
                    vec![CredentialFormat::LdpVc]
                ),
            ]
        );

        let by_query = |id: &str| {
            permission_request
                .credentials()
                .into_iter()
                .filter(|credential| credential.credential_query_id == id)
                .collect::<Vec<_>>()
        };

        let vp_token = present_selected(&permission_request, by_query("mdl"))
            .await
            .expect("the mdl option alone");
        assert_eq!(
            vp_token.as_object().unwrap().keys().collect::<Vec<_>>(),
            ["mdl"]
        );

        let vp_token = present_selected(&permission_request, by_query("identity"))
            .await
            .expect("the identity option alone");
        assert_eq!(
            vp_token.as_object().unwrap().keys().collect::<Vec<_>>(),
            ["identity"]
        );
    }

    #[tokio::test]
    async fn mixed_format_credential_sets_accept_both_options_together() {
        let permission_request = mixed_format_permission_request_with_sets(
            1,
            false,
            Some(serde_json::json!([{ "options": [["mdl"], ["identity"]] }])),
        )
        .await;

        let both = permission_request
            .credentials()
            .into_iter()
            .filter(|credential| credential.credential_query_id != "alumni")
            .collect();
        let vp_token = present_selected(&permission_request, both)
            .await
            .expect("no check rejects a second option today");

        let mut queries: Vec<&String> = vp_token.as_object().unwrap().keys().collect();
        queries.sort();
        assert_eq!(queries, ["identity", "mdl"]);
    }

    // ---- DynamicCredentialProvider hook ----

    use crate::oid4vp::dynamic_credential::{
        DcqlCredentialQueryJson, DynamicCredentialError, DynamicCredentialOffer,
        DynamicCredentialProvider, IssuedCredential, PresentationBinding,
    };
    use serde_json::json;

    /// A mock provider that offers to mint a credential for any `jwt_vc_json`
    /// credential query and mints a fixed `vp_token` string bound to the
    /// presentation nonce.
    #[derive(Debug)]
    struct MockProvider;

    const MOCK_OFFER_ID: &str = "mock-offer";
    const MOCK_TITLE: &str = "Mock minted credential";

    #[async_trait::async_trait]
    impl DynamicCredentialProvider for MockProvider {
        async fn offers(&self, query: DcqlCredentialQueryJson) -> Vec<DynamicCredentialOffer> {
            let query = query.parse().expect("valid DCQL credential query JSON");
            // Only offer for `jwt_vc_json` queries.
            let format = openid4vp::core::credential_format::ClaimFormatDesignation::JwtVcJson;
            if *query.format() != format {
                return vec![];
            }
            vec![DynamicCredentialOffer {
                offer_id: MOCK_OFFER_ID.to_string(),
                credential_query_id: query.id().to_string(),
                title: MOCK_TITLE.to_string(),
            }]
        }

        async fn issue(
            &self,
            offer_id: String,
            binding: PresentationBinding,
        ) -> Result<IssuedCredential, DynamicCredentialError> {
            if offer_id != MOCK_OFFER_ID {
                return Err(DynamicCredentialError::IssuanceFailed(format!(
                    "unknown offer id: {offer_id}"
                )));
            }
            // Echo the binding into the minted token so the test can confirm the
            // live nonce/client_id were threaded through.
            Ok(IssuedCredential {
                vp_token_item: format!("minted:{}:{}", binding.nonce, binding.client_id),
            })
        }
    }

    fn dynamic_request() -> AuthorizationRequestObject {
        let request = json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-dyn",
            "nonce": "nonce-dyn",
            "client_metadata": {
                "vp_formats_supported": {
                    "jwt_vc_json": { "alg_values": ["ES256"] }
                }
            },
            "dcql_query": {
                "credentials": [
                    {
                        "id": "mintable_0",
                        "format": "jwt_vc_json"
                    }
                ]
            }
        })
        .to_string();

        serde_json::from_str(&request).expect("failed to parse dynamic test request")
    }

    #[tokio::test]
    async fn dynamic_provider_offer_select_mint() {
        let signer = KeySigner {
            jwk: JWK::generate_p256(),
        };

        // Holder with no stored credentials, only the mock dynamic provider,
        // registered via the additive `*_and_providers` constructor.
        let holder = Holder::new_with_credentials_and_providers(
            vec![],
            vec![],
            Box::new(signer),
            String::new(),
            None,
            None,
            vec![Arc::new(MockProvider)],
        )
        .await
        .expect("failed to construct holder");

        let permission_request = holder
            .authorization_request(AuthRequest::Request(Box::new(dynamic_request())))
            .await
            .expect("failed to build permission request");

        // Stored credentials are unchanged (none here); the provider's offer is
        // surfaced via the parallel `dynamic_offers()` accessor.
        assert!(permission_request.credentials().is_empty());
        let offers = permission_request.dynamic_offers();
        assert_eq!(offers.len(), 1);
        let offer = &offers[0];
        assert_eq!(offer.offer_id, MOCK_OFFER_ID);
        assert_eq!(offer.credential_query_id, "mintable_0");
        assert_eq!(offer.title, MOCK_TITLE);

        // Select the offer and build the response via the additive method. No
        // stored credentials are selected here.
        let response = permission_request
            .create_permission_response_with_offers(
                vec![],
                vec![],
                vec![offer.clone()],
                ResponseOptions::default(),
            )
            .await
            .expect("failed to create permission response");

        // The minted item lands in the vp_token under the offer's
        // credential_query_id, bound to the request's nonce/client_id.
        let vp_token_json = response.vp_token().expect("failed to serialize vp_token");
        let vp_token: serde_json::Value =
            serde_json::from_str(&vp_token_json).expect("vp_token is valid JSON");

        let items = vp_token
            .get("mintable_0")
            .and_then(|v| v.as_array())
            .expect("vp_token contains an array for mintable_0");
        assert_eq!(items.len(), 1);
        assert_eq!(
            items[0].as_str().unwrap(),
            "minted:nonce-dyn:redirect_uri:https://wallet.example/callback"
        );
    }

    // ---- Client authentication ----

    async fn bare_holder() -> Arc<Holder> {
        Holder::new_with_credentials(
            vec![],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            None,
            None,
        )
        .await
        .expect("failed to construct holder")
    }

    fn unsigned_jwt(header: serde_json::Value, claims: serde_json::Value) -> String {
        use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
        format!(
            "{}.{}.",
            BASE64_URL_SAFE_NO_PAD.encode(header.to_string()),
            BASE64_URL_SAFE_NO_PAD.encode(claims.to_string())
        )
    }

    #[test]
    fn metadata_does_not_advertise_unsigned_request_objects() {
        let metadata = serde_json::to_value(Holder::metadata().unwrap()).unwrap();
        assert_eq!(
            metadata["request_object_signing_alg_values_supported"],
            json!(["ES256"])
        );
    }

    #[tokio::test]
    async fn decentralized_identifier_refuses_unsigned_request_objects() {
        let holder = bare_holder().await;
        let claims = json!({
            "client_id": "decentralized_identifier:did:web:verifier.example",
            "response_uri": "https://attacker.example/post",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        });
        let request: AuthorizationRequestObject =
            serde_json::from_value(claims.clone()).expect("invalid request");
        let jwt = unsigned_jwt(
            json!({ "alg": "none", "kid": "did:web:verifier.example#key-1" }),
            claims,
        );

        let err = holder
            .decentralized_identifier(&request, Some(jwt.clone()))
            .await
            .expect_err("an unsigned decentralized_identifier request must be refused");
        assert!(format!("{err:#}").contains("unsigned"), "{err:#}");

        // Without `none` in the metadata, the crate check refuses it too.
        let resolver: VerificationMethodDIDResolver<DIDWeb, AnyJwkMethod> =
            VerificationMethodDIDResolver::new(DIDWeb);
        assert!(
            verify_with_resolver(&holder.metadata, &request, jwt, None, &resolver)
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn redirect_uri_requests_are_bound_to_the_response_uri() {
        let holder = bare_holder().await;

        // `dynamic_request` posts to its own Client Identifier.
        holder
            .redirect_uri(&dynamic_request(), None)
            .await
            .expect("a bound redirect_uri request must be accepted");

        let mut claims = serde_json::to_value(dynamic_request()).unwrap();
        claims["response_uri"] = json!("https://attacker.example/post");
        let unbound: AuthorizationRequestObject =
            serde_json::from_value(claims).expect("invalid request");
        assert!(holder.redirect_uri(&unbound, None).await.is_err());
    }

    #[tokio::test]
    async fn redirect_uri_refuses_signed_request_objects() {
        let holder = bare_holder().await;
        let request = dynamic_request();
        let claims = serde_json::to_value(&request).unwrap();

        let unsigned = unsigned_jwt(
            json!({ "alg": "none", "typ": "oauth-authz-req+jwt" }),
            claims.clone(),
        );
        assert!(holder.redirect_uri(&request, Some(unsigned)).await.is_ok());

        let signed = unsigned_jwt(
            json!({ "alg": "ES256", "typ": "oauth-authz-req+jwt" }),
            claims,
        );
        assert!(holder.redirect_uri(&request, Some(signed)).await.is_err());
    }

    // ---- Client authentication through `authorization_request` ----

    /// An `openid4vp://` Authorization Request URL with these parameters.
    fn request_url(params: &[(&str, &str)]) -> Url {
        Url::parse_with_params("openid4vp://", params).expect("invalid request URL")
    }

    async fn refused(holder: &Holder, url: Url) -> OID4VPError {
        match holder.authorization_request(AuthRequest::Url(url)).await {
            Ok(_) => panic!("the request must be refused"),
            Err(err) => err,
        }
    }

    /// Asserts that client authentication refused the request.
    fn assert_refused_by_client_auth(err: &OID4VPError, reason: &str) {
        match err {
            OID4VPError::RequestValidation(msg) => assert!(msg.contains(reason), "{msg}"),
            err => panic!("expected a RequestValidation error, got {err:?}"),
        }
    }

    fn unsigned_did_claims() -> serde_json::Value {
        json!({
            "client_id": "decentralized_identifier:did:web:verifier.example",
            "response_uri": "https://attacker.example/post",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        })
    }

    fn unsigned_did_request_object() -> String {
        unsigned_jwt(
            json!({
                "alg": "none",
                "typ": "oauth-authz-req+jwt",
                "kid": "did:web:verifier.example#key-1"
            }),
            unsigned_did_claims(),
        )
    }

    #[tokio::test]
    async fn an_unsigned_did_request_object_by_value_is_refused() {
        let holder = bare_holder().await;
        let url = request_url(&[
            (
                "client_id",
                "decentralized_identifier:did:web:verifier.example",
            ),
            ("request", &unsigned_did_request_object()),
        ]);
        assert_refused_by_client_auth(&refused(&holder, url).await, "unsigned");
    }

    #[tokio::test]
    async fn an_unsigned_did_request_object_by_reference_is_refused() {
        use wiremock::{
            matchers::{method, path},
            Mock, MockServer, ResponseTemplate,
        };

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/request"))
            .respond_with(ResponseTemplate::new(200).set_body_string(unsigned_did_request_object()))
            .expect(1)
            .mount(&server)
            .await;

        let holder = bare_holder().await;
        let url = request_url(&[
            (
                "client_id",
                "decentralized_identifier:did:web:verifier.example",
            ),
            ("request_uri", &format!("{}/request", server.uri())),
        ]);
        assert_refused_by_client_auth(&refused(&holder, url).await, "unsigned");
    }

    fn redirect_uri_params(response_uri: &str) -> Vec<(&'static str, String)> {
        vec![
            (
                "client_id",
                "redirect_uri:https://verifier.example/post".into(),
            ),
            ("response_uri", response_uri.into()),
            ("response_type", "vp_token".into()),
            ("response_mode", "direct_post".into()),
            ("nonce", "n".into()),
            (
                "dcql_query",
                json!({ "credentials": [{ "id": "q", "format": "jwt_vc_json" }] }).to_string(),
            ),
        ]
    }

    fn url_from(params: &[(&'static str, String)]) -> Url {
        let params: Vec<(&str, &str)> = params.iter().map(|(k, v)| (*k, v.as_str())).collect();
        request_url(&params)
    }

    #[tokio::test]
    async fn a_bound_redirect_uri_request_passes_client_auth() {
        let holder = bare_holder().await;
        let url = url_from(&redirect_uri_params("https://verifier.example/post"));

        // The DCQL query is resolved after client authentication. Plain URL
        // parameters are strings, and the crate does not parse a JSON string
        // as a DCQL query.
        let err = refused(&holder, url).await;
        assert!(
            matches!(err, OID4VPError::DcqlQueryResolution(_)),
            "{err:?}"
        );
    }

    #[tokio::test]
    async fn an_unbound_redirect_uri_request_is_refused() {
        let holder = bare_holder().await;
        let url = url_from(&redirect_uri_params("https://attacker.example/post"));
        assert_refused_by_client_auth(&refused(&holder, url).await, "does not match");
    }

    /// The crate compares the URL's `client_id` with the Request Object's
    /// after client authentication (`AuthorizationRequest::validate`).
    #[tokio::test]
    async fn a_request_object_with_another_client_id_than_the_url_is_refused() {
        let holder = bare_holder().await;
        // Self-consistent on its own: it posts to its own Client Identifier.
        let request_object = unsigned_jwt(
            json!({ "alg": "none", "typ": "oauth-authz-req+jwt" }),
            json!({
                "client_id": "redirect_uri:https://attacker.example/post",
                "response_uri": "https://attacker.example/post",
                "response_type": "vp_token",
                "response_mode": "direct_post",
                "nonce": "n",
                "dcql_query": { "credentials": [] }
            }),
        );
        let url = request_url(&[
            ("client_id", "redirect_uri:https://verifier.example/post"),
            ("request", &request_object),
        ]);
        assert_refused_by_client_auth(&refused(&holder, url).await, "different client ids");
    }

    /// `dc_api` requests have no response URI, and the crate's placeholder
    /// `return_uri()` is `https://example.com/`. A `redirect_uri` request
    /// naming that placeholder passes client authentication, and the holder
    /// then refuses the response mode.
    #[tokio::test]
    async fn a_dc_api_request_is_refused_after_client_auth() {
        let holder = bare_holder().await;
        let url = request_url(&[
            ("client_id", "redirect_uri:https://example.com"),
            ("response_type", "vp_token"),
            ("response_mode", "dc_api"),
            ("nonce", "n"),
            ("dcql_query", r#"{"credentials":[]}"#),
        ]);
        let err = refused(&holder, url).await;
        assert!(
            matches!(err, OID4VPError::UnsupportedResponseMode(_)),
            "{err:?}"
        );
    }

    // ---- Signed Request Objects ----

    /// `header.payload` of a compact JWS, ready to sign.
    fn signing_input(header: &serde_json::Value, claims: &serde_json::Value) -> String {
        use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
        format!(
            "{}.{}",
            BASE64_URL_SAFE_NO_PAD.encode(header.to_string()),
            BASE64_URL_SAFE_NO_PAD.encode(claims.to_string())
        )
    }

    fn compact_jws(signing_input: &str, signature: &[u8]) -> String {
        use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
        format!(
            "{signing_input}.{}",
            BASE64_URL_SAFE_NO_PAD.encode(signature)
        )
    }

    /// The same JWS with one byte of its signature changed.
    pub(crate) fn tamper_signature(jws: &str) -> String {
        use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
        let (signing_input, signature) = jws.rsplit_once('.').expect("not a compact JWS");
        let mut signature = BASE64_URL_SAFE_NO_PAD
            .decode(signature)
            .expect("invalid signature encoding");
        *signature.last_mut().expect("empty signature") ^= 0x01;
        compact_jws(signing_input, &signature)
    }

    /// An ES256 Request Object signed with a new P-256 `did:key`, with `kid`
    /// set to the key's verification method. `claims` gets the DID.
    pub(crate) async fn sign_with_did_key(
        claims: impl FnOnce(&str) -> serde_json::Value,
    ) -> (serde_json::Value, String) {
        let jwk = JWK::generate_p256();
        let kid = ssi::dids::DIDKey::generate_url(&jwk)
            .expect("failed to generate a did:key")
            .to_string();
        let (did, _) = kid.split_once('#').expect("no fragment in the did:key URL");
        let claims = claims(did);
        let header = json!({ "alg": "ES256", "typ": "oauth-authz-req+jwt", "kid": kid });
        let input = signing_input(&header, &claims);
        let signature = jwk
            .sign_bytes(input.as_bytes())
            .await
            .expect("failed to sign the Request Object");
        (claims, compact_jws(&input, &signature))
    }

    /// The holder resolves `did:web`, which needs the network, so this calls
    /// the crate's `verify_with_resolver` directly with a `did:key` resolver,
    /// and the holder's metadata.
    #[tokio::test]
    async fn a_did_signed_request_object_is_accepted_and_a_tampered_one_refused() {
        let metadata = Holder::metadata().unwrap();
        let resolver: VerificationMethodDIDResolver<ssi::dids::DIDKey, AnyJwkMethod> =
            VerificationMethodDIDResolver::new(ssi::dids::DIDKey);
        let (claims, jwt) = sign_with_did_key(|did| {
            json!({
                "client_id": format!("decentralized_identifier:{did}"),
                "response_uri": "https://verifier.example/post",
                "response_type": "vp_token",
                "response_mode": "direct_post",
                "nonce": "n",
                "dcql_query": { "credentials": [] }
            })
        })
        .await;
        let request: AuthorizationRequestObject =
            serde_json::from_value(claims).expect("invalid request");

        client_auth::ensure_signed(&jwt).expect("the Request Object is signed");
        verify_with_resolver(&metadata, &request, jwt.clone(), None, &resolver)
            .await
            .expect("a correctly signed request must be accepted");

        let tampered = tamper_signature(&jwt);
        client_auth::ensure_signed(&tampered).expect("the header still says ES256");
        let err = verify_with_resolver(&metadata, &request, tampered, None, &resolver)
            .await
            .expect_err("a tampered signature must be refused");
        assert!(format!("{err:#}").contains("signature"), "{err:#}");
    }

    /// A self-signed P-256 leaf certificate (DER) whose only SAN is the DNS
    /// name `dns_name`, and its key.
    fn self_signed_leaf(dns_name: &str) -> (Vec<u8>, p256::ecdsa::SigningKey) {
        use x509_cert::{
            builder::{Builder, CertificateBuilder, Profile},
            der::{asn1::Ia5String, Encode},
            ext::pkix::{name::GeneralName, SubjectAltName},
            spki::SubjectPublicKeyInfoOwned,
            time::Validity,
        };

        let key = p256::ecdsa::SigningKey::random(&mut ssi::crypto::rand::thread_rng());
        let spki = SubjectPublicKeyInfoOwned::from_key(*key.verifying_key()).unwrap();
        let mut builder = CertificateBuilder::new(
            Profile::Manual { issuer: None },
            1u64.into(),
            Validity::from_now(std::time::Duration::from_secs(60 * 60)).unwrap(),
            format!("CN={dns_name}").parse().unwrap(),
            spki,
            &key,
        )
        .unwrap();
        builder
            .add_extension(&SubjectAltName(vec![GeneralName::DnsName(
                Ia5String::new(dns_name).unwrap(),
            )]))
            .unwrap();
        let certificate = builder.build::<p256::ecdsa::DerSignature>().unwrap();
        (certificate.to_der().unwrap(), key)
    }

    /// An ES256 Request Object with the certificate in `x5c`, signed with
    /// its key.
    fn sign_with_leaf(
        certificate: &[u8],
        key: &p256::ecdsa::SigningKey,
        claims: &serde_json::Value,
    ) -> String {
        use base64::prelude::{Engine as _, BASE64_STANDARD};
        use p256::ecdsa::signature::Signer as _;

        let header = json!({
            "alg": "ES256",
            "typ": "oauth-authz-req+jwt",
            "x5c": [BASE64_STANDARD.encode(certificate)]
        });
        let input = signing_input(&header, claims);
        let signature: p256::ecdsa::Signature = key.sign(input.as_bytes());
        compact_jws(&input, &signature.to_bytes())
    }

    fn x509_claims(client_id: &str) -> serde_json::Value {
        json!({
            "client_id": client_id,
            "response_uri": "https://verifier.example/post",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        })
    }

    #[tokio::test]
    async fn an_x509_san_dns_signed_request_object_is_accepted_and_a_tampered_one_refused() {
        let holder = bare_holder().await;
        let (certificate, key) = self_signed_leaf("verifier.example");
        let claims = x509_claims("x509_san_dns:verifier.example");
        let request: AuthorizationRequestObject =
            serde_json::from_value(claims.clone()).expect("invalid request");
        let jwt = sign_with_leaf(&certificate, &key, &claims);

        holder
            .x509_san_dns(&request, Some(jwt.clone()))
            .await
            .expect("a correctly signed request must be accepted");

        let err = holder
            .x509_san_dns(&request, Some(tamper_signature(&jwt)))
            .await
            .expect_err("a tampered signature must be refused");
        assert!(format!("{err:#}").contains("signature"), "{err:#}");

        // A certificate for another host does not authenticate this client_id.
        let (other_certificate, other_key) = self_signed_leaf("attacker.example");
        let other = sign_with_leaf(&other_certificate, &other_key, &claims);
        assert!(holder.x509_san_dns(&request, Some(other)).await.is_err());
    }

    #[tokio::test]
    async fn an_x509_hash_signed_request_object_is_accepted_and_a_tampered_one_refused() {
        use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
        use sha2::Digest as _;

        let holder = bare_holder().await;
        let (certificate, key) = self_signed_leaf("verifier.example");
        let hash = BASE64_URL_SAFE_NO_PAD.encode(sha2::Sha256::digest(&certificate));
        let claims = x509_claims(&format!("x509_hash:{hash}"));
        let request: AuthorizationRequestObject =
            serde_json::from_value(claims.clone()).expect("invalid request");
        let jwt = sign_with_leaf(&certificate, &key, &claims);

        holder
            .x509_hash(&request, Some(jwt.clone()))
            .await
            .expect("a correctly signed request must be accepted");

        let err = holder
            .x509_hash(&request, Some(tamper_signature(&jwt)))
            .await
            .expect_err("a tampered signature must be refused");
        assert!(format!("{err:#}").contains("signature"), "{err:#}");

        // Another certificate does not match the hash.
        let (other_certificate, other_key) = self_signed_leaf("verifier.example");
        let other = sign_with_leaf(&other_certificate, &other_key, &claims);
        assert!(holder.x509_hash(&request, Some(other)).await.is_err());
    }

    pub(crate) async fn denial_verifier(
        reply: serde_json::Value,
    ) -> (wiremock::MockServer, serde_json::Value) {
        use wiremock::{
            matchers::{method, path},
            Mock, MockServer, ResponseTemplate,
        };

        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/response"))
            .respond_with(ResponseTemplate::new(200).set_body_json(reply))
            .expect(1)
            .mount(&server)
            .await;

        let response_uri = format!("{}/response", server.uri());
        let request = json!({
            "client_id": format!("redirect_uri:{response_uri}"),
            "response_uri": response_uri,
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "transaction-state",
            "nonce": "nonce-denial",
            "client_metadata": {
                "vp_formats_supported": {
                    "ldp_vc": { "proof_type_values": ["ecdsa-rdfc-2019"] }
                }
            },
            "dcql_query": {
                "credentials": [{
                    "id": "credential",
                    "format": "ldp_vc"
                }]
            }
        });
        (server, request)
    }

    pub(crate) async fn assert_access_denied_posted(server: &wiremock::MockServer) {
        let received = server.received_requests().await.unwrap();
        assert_eq!(received.len(), 1);
        let body = String::from_utf8(received[0].body.clone()).unwrap();
        assert!(
            body.contains("error=access_denied"),
            "unexpected body: {body}"
        );
        assert!(
            body.contains("state=transaction-state"),
            "unexpected body: {body}"
        );
        assert!(!body.contains("vp_token"), "unexpected body: {body}");
    }

    async fn deny(reply: serde_json::Value) -> (wiremock::MockServer, Option<Url>) {
        let (server, request) = denial_verifier(reply).await;
        let alumni =
            JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into())
                .expect("failed to parse credential");
        let holder = Holder::new_with_credentials(
            vec![ParsedCredential::new_ldp_vc(alumni)],
            vec![],
            Box::new(KeySigner {
                jwk: JWK::generate_p256(),
            }),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .expect("failed to construct holder");
        let request: AuthorizationRequestObject =
            serde_json::from_value(request).expect("failed to parse request");
        let permission_request = holder
            .authorization_request(AuthRequest::Request(Box::new(request)))
            .await
            .expect("failed to build permission request");

        let redirect = holder
            .deny_permission(permission_request)
            .await
            .expect("failed to deny permission");
        (server, redirect)
    }

    #[tokio::test]
    async fn deny_permission_posts_access_denied_with_state() {
        let (server, redirect) = deny(json!({})).await;
        assert_eq!(redirect, None);
        assert_access_denied_posted(&server).await;
    }

    #[tokio::test]
    async fn deny_permission_returns_the_verifier_redirect_uri() {
        let (server, redirect) =
            deny(json!({ "redirect_uri": "https://verifier.example/denied" })).await;
        assert_eq!(
            redirect,
            Some(Url::parse("https://verifier.example/denied").unwrap())
        );
        assert_access_denied_posted(&server).await;
    }
}
