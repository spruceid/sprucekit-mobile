#![allow(deprecated)]

use std::collections::HashMap;
use std::sync::Arc;

use crate::credential::{ParsedCredential, PresentableCredential};
use crate::crypto::KeyStore;
use crate::vdc_collection::VdcCollection;

use super::draft18::{
    credential::Draft18PresentableCredential,
    holder::Draft18AuthRequest,
    permission_request::{
        Draft18PermissionRequest, Draft18PermissionResponse, Draft18RequestedField,
        Draft18ResponseOptions,
    },
    presentation::{Draft18PresentationError, Draft18PresentationSigner},
    Draft18Holder,
};
use super::holder::{AuthRequest, Holder};
use super::permission_request::{
    PermissionRequest, PermissionRequestError, PermissionResponse, RequestedField, ResponseOptions,
};
use super::presentation::{PresentationError, PresentationSigner};
use super::{select_oid4vp_version, Oid4vpVersion};
use crate::oid4vp::draft18::error::Draft18OID4VPError;
use crate::oid4vp::error::OID4VPError;
use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
use serde_json::{Map, Value};
use ssi::claims::data_integrity::CryptosuiteString;
use ssi::crypto::Algorithm;

use url::Url;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Oid4vpFacadeError {
    #[error("Unsupported OID4VP request shape.")]
    UnsupportedRequest,
    #[error("Failed to parse OID4VP request: {0}")]
    RequestParsing(String),
    #[error("Version-specific OID4VP values were mixed in a single operation.")]
    VersionMismatch,
    #[error("Draft 13 and Draft 18 cannot both be supported: a bare request_uri is indistinguishable between them before its single-use fetch.")]
    ConflictingVersions,
    #[error(transparent)]
    V1(#[from] OID4VPError),
    #[error(transparent)]
    Draft18(#[from] Draft18OID4VPError),
}

impl From<PermissionRequestError> for Oid4vpFacadeError {
    fn from(value: PermissionRequestError) -> Self {
        OID4VPError::from(value).into()
    }
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct Oid4vpResponseOptions {
    pub force_array_serialization: bool,
    pub should_strip_quotes: bool,
    pub remove_vp_path_prefix: bool,
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(Debug, Clone, uniffi::Record)]
pub struct Oid4vpRequestedField {
    pub id: uuid::Uuid,
    pub match_id: String,
    pub name: Option<String>,
    pub path: String,
    pub required: bool,
    pub retained: bool,
    pub purpose: Option<String>,
    pub raw_fields: Vec<String>,
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(Debug, Clone, uniffi::Record)]
pub struct Oid4vpRequirement {
    pub id: String,
    pub display_name: String,
    pub required: bool,
    pub credentials: Vec<Arc<Oid4vpPresentableCredential>>,
}

#[derive(Debug)]
enum Oid4vpHolderSource {
    Collection(Arc<VdcCollection>),
    Credentials(Vec<Arc<ParsedCredential>>),
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(uniffi::Object)]
pub struct Oid4vpHolder {
    source: Oid4vpHolderSource,
    trusted_dids: Vec<String>,
    signer: Arc<Box<dyn Oid4vpPresentationSigner>>,
    key_map: HashMap<String, String>,
    fallback_key_id: String,
    context_map: Option<HashMap<String, String>>,
    keystore: Option<Arc<dyn KeyStore>>,
}

impl std::fmt::Debug for Oid4vpHolder {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Oid4vpHolder")
            .field("trusted_dids", &self.trusted_dids)
            .field("context_map", &self.context_map)
            .field("keystore", &self.keystore.as_ref().map(|_| "KeyStore"))
            .finish()
    }
}

#[derive(Debug)]
enum Oid4vpSessionInner {
    V1 {
        holder: Arc<Holder>,
        request: Arc<PermissionRequest>,
    },
    Draft18 {
        holder: Arc<Draft18Holder>,
        request: Arc<Draft18PermissionRequest>,
        /// The version reported to the caller. Almost always `Draft18`, but the
        /// draft-13 path reuses this same draft-18 machinery (the request is
        /// translated upstream) and sets this to `Draft13` so `version()` and
        /// the Flutter surface report the negotiated version faithfully.
        presented_as: Oid4vpVersion,
    },
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(Debug, uniffi::Object)]
pub struct Oid4vpSession {
    inner: Oid4vpSessionInner,
}

#[derive(Debug)]
enum Oid4vpPresentableCredentialInner {
    V1(Arc<PresentableCredential>),
    Draft18(Arc<Draft18PresentableCredential>),
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(Debug, uniffi::Object)]
pub struct Oid4vpPresentableCredential {
    inner: Oid4vpPresentableCredentialInner,
}

#[derive(Debug)]
enum Oid4vpPermissionResponseInner {
    V1(Arc<PermissionResponse>),
    Draft18(Arc<Draft18PermissionResponse>),
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(Debug, uniffi::Object)]
pub struct Oid4vpPermissionResponse {
    inner: Oid4vpPermissionResponseInner,
}

#[deprecated(
    note = "Compatibility facade for legacy OID4VP integrations only. Prefer the OID4VP v1 APIs for new integrations; this facade may be removed in a future release."
)]
#[uniffi::export(callback_interface)]
#[async_trait::async_trait]
pub trait Oid4vpPresentationSigner: Send + Sync + std::fmt::Debug {
    async fn sign(&self, key_id: String, payload: Vec<u8>) -> Result<Vec<u8>, Oid4vpFacadeError>;
    fn algorithm(&self) -> Algorithm;
    async fn verification_method(&self, key_id: String) -> String;
    fn did(&self, key_id: String) -> String;
    fn cryptosuite(&self) -> CryptosuiteString;
    fn jwk(&self, key_id: String) -> String;
}

#[derive(Debug)]
struct V1SignerAdapter {
    signer: Arc<Box<dyn Oid4vpPresentationSigner>>,
}

#[async_trait::async_trait]
impl PresentationSigner for V1SignerAdapter {
    // Forward the per-credential `key_id` to the foreign signer.
    async fn sign(&self, key_id: String, payload: Vec<u8>) -> Result<Vec<u8>, PresentationError> {
        self.signer
            .sign(key_id, payload)
            .await
            .map_err(|e| PresentationError::Signing(e.to_string()))
    }

    fn algorithm(&self) -> Algorithm {
        self.signer.algorithm()
    }

    async fn verification_method(&self, key_id: String) -> String {
        self.signer.verification_method(key_id).await
    }

    fn did(&self, key_id: String) -> String {
        self.signer.did(key_id)
    }

    fn cryptosuite(&self) -> CryptosuiteString {
        self.signer.cryptosuite()
    }

    fn jwk(&self, key_id: String) -> String {
        self.signer.jwk(key_id)
    }
}

#[derive(Debug)]
struct Draft18SignerAdapter {
    signer: Arc<Box<dyn Oid4vpPresentationSigner>>,
}

#[async_trait::async_trait]
impl Draft18PresentationSigner for Draft18SignerAdapter {
    // Forward the per-credential `key_id` to the foreign signer.
    async fn sign(
        &self,
        key_id: String,
        payload: Vec<u8>,
    ) -> Result<Vec<u8>, Draft18PresentationError> {
        self.signer
            .sign(key_id, payload)
            .await
            .map_err(|e| Draft18PresentationError::Signing(e.to_string()))
    }

    fn algorithm(&self) -> Algorithm {
        self.signer.algorithm()
    }

    async fn verification_method(&self, key_id: String) -> String {
        self.signer.verification_method(key_id).await
    }

    fn did(&self, key_id: String) -> String {
        self.signer.did(key_id)
    }

    fn cryptosuite(&self) -> CryptosuiteString {
        self.signer.cryptosuite()
    }

    fn jwk(&self, key_id: String) -> String {
        self.signer.jwk(key_id)
    }
}

#[allow(deprecated)]
#[uniffi::export(async_runtime = "tokio")]
impl Oid4vpHolder {
    #[uniffi::constructor]
    pub async fn new(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn Oid4vpPresentationSigner>,
        key_map: HashMap<String, String>,
        fallback_key_id: String,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, Oid4vpFacadeError> {
        Ok(Arc::new(Self {
            source: Oid4vpHolderSource::Collection(vdc_collection),
            trusted_dids,
            signer: Arc::new(signer),
            key_map,
            fallback_key_id,
            context_map,
            keystore,
        }))
    }

    #[uniffi::constructor]
    pub async fn new_with_credentials(
        provided_credentials: Vec<Arc<ParsedCredential>>,
        trusted_dids: Vec<String>,
        signer: Box<dyn Oid4vpPresentationSigner>,
        key_map: HashMap<String, String>,
        fallback_key_id: String,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, Oid4vpFacadeError> {
        Ok(Arc::new(Self {
            source: Oid4vpHolderSource::Credentials(provided_credentials),
            trusted_dids,
            signer: Arc::new(signer),
            key_map,
            fallback_key_id,
            context_map,
            keystore,
        }))
    }

    pub async fn start(&self, request: String) -> Result<Arc<Oid4vpSession>, Oid4vpFacadeError> {
        self.start_with_supported_versions(request, Vec::new())
            .await
    }

    /// Start a session, restricting version selection to `supported_versions`.
    ///
    /// An empty list means "any version" (auto-detection). A non-empty list
    /// excludes every version not listed, so an unsupported version is never
    /// selected — and never gets to consume a single-use `request_uri` on a
    /// wrong-version fetch. Order is irrelevant; the list gates membership only.
    ///
    /// Draft 13 and Draft 18 may not both be supported: a bare `request_uri`
    /// is indistinguishable between them until its single-use fetch, so keeping
    /// both in scope would reintroduce the wrong-version-burns-the-request bug.
    /// That combination is rejected up front with [`Oid4vpFacadeError::ConflictingVersions`].
    pub async fn start_with_supported_versions(
        &self,
        request: String,
        supported_versions: Vec<Oid4vpVersion>,
    ) -> Result<Arc<Oid4vpSession>, Oid4vpFacadeError> {
        if supported_versions.contains(&Oid4vpVersion::Draft13)
            && supported_versions.contains(&Oid4vpVersion::Draft18)
        {
            return Err(Oid4vpFacadeError::ConflictingVersions);
        }
        let version = select_oid4vp_version(&request, &supported_versions);
        self.start_version(version, &request).await
    }

    async fn start_version(
        &self,
        version: Oid4vpVersion,
        request: &str,
    ) -> Result<Arc<Oid4vpSession>, Oid4vpFacadeError> {
        match version {
            Oid4vpVersion::V1 => {
                let holder = self.new_v1_holder().await?;
                let permission_request = holder
                    .authorization_request(parse_v1_auth_request(request)?)
                    .await?;

                Ok(Arc::new(Oid4vpSession {
                    inner: Oid4vpSessionInner::V1 {
                        holder,
                        request: permission_request,
                    },
                }))
            }
            Oid4vpVersion::Draft18 => {
                let holder = self.new_draft18_holder().await?;
                let permission_request = holder
                    .authorization_request(parse_draft18_auth_request(request)?)
                    .await?;

                Ok(Arc::new(Oid4vpSession {
                    inner: Oid4vpSessionInner::Draft18 {
                        holder,
                        request: permission_request,
                        presented_as: Oid4vpVersion::Draft18,
                    },
                }))
            }
            Oid4vpVersion::Draft13 => {
                // Draft 13 reuses the entire draft-18 engine: only the request's
                // response convention is translated (post -> direct_post,
                // redirect_uri -> response_uri). PE matching, response building,
                // and the form-POST submission to the verifier are identical, and
                // the resulting POST is exactly the draft-13 §7.2 response.
                let holder = self.new_draft18_holder().await?;
                let translated = draft13_request_to_draft18(request).await?;
                let permission_request = holder.authorization_request(translated).await?;

                Ok(Arc::new(Oid4vpSession {
                    inner: Oid4vpSessionInner::Draft18 {
                        holder,
                        request: permission_request,
                        presented_as: Oid4vpVersion::Draft13,
                    },
                }))
            }
            Oid4vpVersion::Unsupported => Err(Oid4vpFacadeError::UnsupportedRequest),
        }
    }

    async fn new_v1_holder(&self) -> Result<Arc<Holder>, Oid4vpFacadeError> {
        let signer = Box::new(V1SignerAdapter {
            signer: self.signer.clone(),
        });

        match &self.source {
            Oid4vpHolderSource::Collection(vdc_collection) => Holder::new(
                vdc_collection.clone(),
                self.trusted_dids.clone(),
                signer,
                self.key_map.clone(),
                self.fallback_key_id.clone(),
                self.context_map.clone(),
                self.keystore.clone(),
            )
            .await
            .map_err(Into::into),
            Oid4vpHolderSource::Credentials(credentials) => Holder::new_with_credentials(
                credentials.clone(),
                self.trusted_dids.clone(),
                signer,
                self.key_map.clone(),
                self.fallback_key_id.clone(),
                self.context_map.clone(),
                self.keystore.clone(),
            )
            .await
            .map_err(Into::into),
        }
    }

    async fn new_draft18_holder(&self) -> Result<Arc<Draft18Holder>, Oid4vpFacadeError> {
        let signer = Box::new(Draft18SignerAdapter {
            signer: self.signer.clone(),
        });

        match &self.source {
            Oid4vpHolderSource::Collection(vdc_collection) => Draft18Holder::new(
                vdc_collection.clone(),
                self.trusted_dids.clone(),
                signer,
                self.key_map.clone(),
                self.fallback_key_id.clone(),
                self.context_map.clone(),
            )
            .await
            .map_err(Into::into),
            Oid4vpHolderSource::Credentials(credentials) => Draft18Holder::new_with_credentials(
                credentials.clone(),
                self.trusted_dids.clone(),
                signer,
                self.key_map.clone(),
                self.fallback_key_id.clone(),
                self.context_map.clone(),
            )
            .await
            .map_err(Into::into),
        }
    }
}

#[allow(deprecated)]
#[uniffi::export(async_runtime = "tokio")]
impl Oid4vpSession {
    pub fn version(&self) -> Oid4vpVersion {
        match &self.inner {
            Oid4vpSessionInner::V1 { .. } => Oid4vpVersion::V1,
            Oid4vpSessionInner::Draft18 { presented_as, .. } => *presented_as,
        }
    }

    pub fn client_id(&self) -> Option<String> {
        match &self.inner {
            Oid4vpSessionInner::V1 { request, .. } => request.client_id(),
            Oid4vpSessionInner::Draft18 { request, .. } => request.client_id(),
        }
    }

    pub fn domain(&self) -> Option<String> {
        match &self.inner {
            Oid4vpSessionInner::V1 { request, .. } => request.domain(),
            Oid4vpSessionInner::Draft18 { request, .. } => request.domain(),
        }
    }

    pub fn purpose(&self) -> Option<String> {
        match &self.inner {
            Oid4vpSessionInner::V1 { .. } => None,
            Oid4vpSessionInner::Draft18 { request, .. } => request.purpose(),
        }
    }

    pub fn credentials(&self) -> Vec<Arc<Oid4vpPresentableCredential>> {
        match &self.inner {
            Oid4vpSessionInner::V1 { request, .. } => request
                .credentials()
                .into_iter()
                .map(Oid4vpPresentableCredential::from_v1)
                .collect(),
            Oid4vpSessionInner::Draft18 { request, .. } => request
                .credentials()
                .into_iter()
                .map(Oid4vpPresentableCredential::from_draft18)
                .collect(),
        }
    }

    pub fn requirements(&self) -> Vec<Oid4vpRequirement> {
        match &self.inner {
            Oid4vpSessionInner::V1 { request, .. } => request
                .credential_requirements()
                .into_iter()
                .map(|req| Oid4vpRequirement {
                    id: req.credential_query_ids.join("|"),
                    display_name: req.display_name,
                    required: req.required,
                    credentials: req
                        .credentials
                        .into_iter()
                        .map(Oid4vpPresentableCredential::from_v1)
                        .collect(),
                })
                .collect(),
            Oid4vpSessionInner::Draft18 { request, .. } => draft18_requirements(request),
        }
    }

    pub fn is_multi_credential_selection(&self) -> bool {
        self.requirements().len() > 1
    }

    pub fn is_multi_credential_matching(&self) -> bool {
        self.requirements()
            .into_iter()
            .any(|requirement| requirement.credentials.len() > 1)
    }

    pub fn requested_fields(
        &self,
        credential: &Arc<Oid4vpPresentableCredential>,
    ) -> Result<Vec<Oid4vpRequestedField>, Oid4vpFacadeError> {
        match (&self.inner, &credential.inner) {
            (
                Oid4vpSessionInner::V1 { request, .. },
                Oid4vpPresentableCredentialInner::V1(cred),
            ) => Ok(request
                .requested_fields(cred)
                .into_iter()
                .map(|field| oid4vp_requested_field_from_v1(&field))
                .collect()),
            (
                Oid4vpSessionInner::Draft18 { request, .. },
                Oid4vpPresentableCredentialInner::Draft18(cred),
            ) => Ok(request
                .requested_fields(cred)
                .into_iter()
                .map(|field| oid4vp_requested_field_from_draft18(&field))
                .collect()),
            _ => Err(Oid4vpFacadeError::VersionMismatch),
        }
    }

    pub async fn create_permission_response(
        &self,
        selected_credentials: Vec<Arc<Oid4vpPresentableCredential>>,
        selected_fields: Vec<Vec<String>>,
        response_options: Oid4vpResponseOptions,
    ) -> Result<Arc<Oid4vpPermissionResponse>, Oid4vpFacadeError> {
        match &self.inner {
            Oid4vpSessionInner::V1 { request, .. } => {
                let credentials = selected_credentials
                    .into_iter()
                    .map(|credential| match &credential.inner {
                        Oid4vpPresentableCredentialInner::V1(inner) => Ok(inner.clone()),
                        _ => Err(Oid4vpFacadeError::VersionMismatch),
                    })
                    .collect::<Result<Vec<_>, _>>()?;

                let response = request
                    .create_permission_response(
                        credentials,
                        selected_fields,
                        ResponseOptions {
                            force_array_serialization: response_options.force_array_serialization,
                        },
                    )
                    .await?;

                Ok(Arc::new(Oid4vpPermissionResponse {
                    inner: Oid4vpPermissionResponseInner::V1(response),
                }))
            }
            Oid4vpSessionInner::Draft18 { request, .. } => {
                let credentials = selected_credentials
                    .into_iter()
                    .map(|credential| match &credential.inner {
                        Oid4vpPresentableCredentialInner::Draft18(inner) => Ok(inner.clone()),
                        _ => Err(Oid4vpFacadeError::VersionMismatch),
                    })
                    .collect::<Result<Vec<_>, _>>()?;

                let response = request
                    .create_permission_response(
                        credentials,
                        selected_fields,
                        Draft18ResponseOptions {
                            should_strip_quotes: response_options.should_strip_quotes,
                            force_array_serialization: response_options.force_array_serialization,
                            remove_vp_path_prefix: response_options.remove_vp_path_prefix,
                        },
                    )
                    .await?;

                Ok(Arc::new(Oid4vpPermissionResponse {
                    inner: Oid4vpPermissionResponseInner::Draft18(response),
                }))
            }
        }
    }

    pub async fn submit_permission_response(
        &self,
        response: Arc<Oid4vpPermissionResponse>,
    ) -> Result<Option<Url>, Oid4vpFacadeError> {
        match (&self.inner, &response.inner) {
            (Oid4vpSessionInner::V1 { holder, .. }, Oid4vpPermissionResponseInner::V1(resp)) => {
                holder
                    .submit_permission_response(resp.clone())
                    .await
                    .map_err(Into::into)
            }
            (
                Oid4vpSessionInner::Draft18 { holder, .. },
                Oid4vpPermissionResponseInner::Draft18(resp),
            ) => holder
                .submit_permission_response(resp.clone())
                .await
                .map_err(Into::into),
            _ => Err(Oid4vpFacadeError::VersionMismatch),
        }
    }
}

#[uniffi::export]
impl Oid4vpPresentableCredential {
    pub fn as_parsed_credential(&self) -> Arc<ParsedCredential> {
        match &self.inner {
            Oid4vpPresentableCredentialInner::V1(credential) => credential.as_parsed_credential(),
            Oid4vpPresentableCredentialInner::Draft18(credential) => {
                credential.as_parsed_credential()
            }
        }
    }

    pub fn selective_disclosable(&self) -> bool {
        match &self.inner {
            Oid4vpPresentableCredentialInner::V1(credential) => credential.selective_disclosable(),
            Oid4vpPresentableCredentialInner::Draft18(credential) => {
                credential.selective_disclosable()
            }
        }
    }

    pub fn match_id(&self) -> String {
        match &self.inner {
            Oid4vpPresentableCredentialInner::V1(credential) => {
                credential.credential_query_id.clone()
            }
            Oid4vpPresentableCredentialInner::Draft18(credential) => {
                credential.input_descriptor_id()
            }
        }
    }

    pub fn version(&self) -> Oid4vpVersion {
        match &self.inner {
            Oid4vpPresentableCredentialInner::V1(_) => Oid4vpVersion::V1,
            Oid4vpPresentableCredentialInner::Draft18(_) => Oid4vpVersion::Draft18,
        }
    }
}

#[uniffi::export]
impl Oid4vpPermissionResponse {
    pub fn selected_credentials(&self) -> Vec<Arc<Oid4vpPresentableCredential>> {
        match &self.inner {
            Oid4vpPermissionResponseInner::V1(response) => response
                .selected_credentials()
                .into_iter()
                .map(Oid4vpPresentableCredential::from_v1)
                .collect(),
            Oid4vpPermissionResponseInner::Draft18(response) => response
                .selected_credentials()
                .into_iter()
                .map(Oid4vpPresentableCredential::from_draft18)
                .collect(),
        }
    }

    pub fn vp_token(&self) -> Result<String, Oid4vpFacadeError> {
        match &self.inner {
            Oid4vpPermissionResponseInner::V1(response) => response.vp_token().map_err(Into::into),
            Oid4vpPermissionResponseInner::Draft18(response) => {
                response.vp_token().map_err(Into::into)
            }
        }
    }

    pub fn version(&self) -> Oid4vpVersion {
        match &self.inner {
            Oid4vpPermissionResponseInner::V1(_) => Oid4vpVersion::V1,
            Oid4vpPermissionResponseInner::Draft18(_) => Oid4vpVersion::Draft18,
        }
    }
}

impl Oid4vpPresentableCredential {
    fn from_v1(credential: Arc<PresentableCredential>) -> Arc<Self> {
        Arc::new(Self {
            inner: Oid4vpPresentableCredentialInner::V1(credential),
        })
    }

    fn from_draft18(credential: Arc<Draft18PresentableCredential>) -> Arc<Self> {
        Arc::new(Self {
            inner: Oid4vpPresentableCredentialInner::Draft18(credential),
        })
    }
}

fn oid4vp_requested_field_from_v1(field: &Arc<RequestedField>) -> Oid4vpRequestedField {
    Oid4vpRequestedField {
        id: field.id(),
        match_id: field.credential_query_id(),
        name: field.name(),
        path: field.path(),
        required: field.required(),
        retained: field.retained(),
        purpose: field.purpose(),
        raw_fields: field.raw_fields(),
    }
}

fn oid4vp_requested_field_from_draft18(field: &Arc<Draft18RequestedField>) -> Oid4vpRequestedField {
    Oid4vpRequestedField {
        id: field.id(),
        match_id: field.input_descriptor_id(),
        name: field.name(),
        path: field.path(),
        required: field.required(),
        retained: field.retained(),
        purpose: field.purpose(),
        raw_fields: field.raw_fields(),
    }
}

#[allow(deprecated)]
fn draft18_requirements(request: &Arc<Draft18PermissionRequest>) -> Vec<Oid4vpRequirement> {
    let mut credentials_by_descriptor: HashMap<String, Vec<Arc<Oid4vpPresentableCredential>>> =
        HashMap::new();

    for credential in request.credentials() {
        credentials_by_descriptor
            .entry(credential.input_descriptor_id())
            .or_default()
            .push(Oid4vpPresentableCredential::from_draft18(credential));
    }

    request
        .definition
        .input_descriptors()
        .iter()
        .map(|descriptor| {
            let display_name = descriptor
                .name
                .clone()
                .or_else(|| descriptor.purpose.clone())
                .unwrap_or_else(|| descriptor.id.clone());

            Oid4vpRequirement {
                id: descriptor.id.clone(),
                display_name,
                required: true,
                credentials: credentials_by_descriptor
                    .remove(&descriptor.id)
                    .unwrap_or_default(),
            }
        })
        .collect()
}

fn parse_v1_auth_request(request: &str) -> Result<AuthRequest, Oid4vpFacadeError> {
    match Url::parse(request) {
        Ok(url) => Ok(AuthRequest::Url(url)),
        Err(_) => serde_json::from_str::<
            openid4vp::core::authorization_request::AuthorizationRequestObject,
        >(request)
        .map(|req| AuthRequest::Request(Box::new(req)))
        .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("{e:?}"))),
    }
}

fn parse_draft18_auth_request(request: &str) -> Result<Draft18AuthRequest, Oid4vpFacadeError> {
    match Url::parse(request) {
        Ok(url) => Ok(Draft18AuthRequest::Url(url)),
        Err(_) => serde_json::from_str::<
            openidvp_draft18::core::authorization_request::AuthorizationRequestObject,
        >(request)
        .map(|req| Draft18AuthRequest::Request(Box::new(req)))
        .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("{e:?}"))),
    }
}

// ---------------------------------------------------------------------------
// OpenID4VP draft-13 → draft-18 request translation
// ---------------------------------------------------------------------------
//
// Draft 13 predates `client_id_scheme`, uses Presentation Exchange (like draft
// 18), and — for the verifier-initiated cross-device flow — uses the bare `post`
// response mode delivering the form-encoded `vp_token` + `presentation_submission`
// to `redirect_uri` (§7.2). Draft 18 spells the same wire exchange as
// `direct_post` delivering to `response_uri`. So a draft-13 request becomes a
// draft-18 one by two edits: `response_mode: post → direct_post` and
// `redirect_uri → response_uri`. Everything downstream (PE matching, response
// building, the form POST to `return_uri`) is the unmodified draft-18 engine, and
// the bytes it POSTs ARE the draft-13 §7.2 response.
//
// The translated object is handed to the draft-18 holder as a pre-parsed
// `Request` (not a `Url`), which skips the draft-18 crate's request-object
// signature verification. Draft-13 requests are commonly unsigned, and signed
// draft-13 request objects cannot be re-signed after translation anyway; this
// matches the existing (finding-tracked) no-verify behavior of the `Request`
// variant. A warning is logged whenever a signed JWT request object is accepted
// without verification.

/// Translate a draft-13 OpenID4VP request (any transport) into a draft-18
/// `AuthorizationRequestObject`, returned as a pre-parsed `Request`.
async fn draft13_request_to_draft18(
    request: &str,
) -> Result<Draft18AuthRequest, Oid4vpFacadeError> {
    let mut params = draft13_collect_params(request).await?;
    draft13_translate_params(&mut params)?;

    let obj: openidvp_draft18::core::authorization_request::AuthorizationRequestObject =
        serde_json::from_value(Value::Object(params)).map_err(|e| {
            Oid4vpFacadeError::RequestParsing(format!("draft-13 → draft-18 translation: {e:?}"))
        })?;
    Ok(Draft18AuthRequest::Request(Box::new(obj)))
}

/// Collect a draft-13 request's parameters into a JSON object, resolving the
/// transport: a bare JSON request object, a `request_uri`/`request` indirection,
/// a compact JWT request object, or an `openid4vp://` URL with inline params.
async fn draft13_collect_params(request: &str) -> Result<Map<String, Value>, Oid4vpFacadeError> {
    // A request object passed by value as JSON.
    if let Ok(Value::Object(map)) = serde_json::from_str::<Value>(request) {
        return draft13_resolve_indirection(map).await;
    }

    // An `openid4vp://`/`https://` link carrying parameters (possibly only
    // `request_uri`/`request`).
    if let Ok(url) = Url::parse(request) {
        if url.query().is_some() {
            let mut map = Map::new();
            for (key, value) in url.query_pairs() {
                map.insert(key.into_owned(), draft13_value(&value));
            }
            return draft13_resolve_indirection(map).await;
        }
    }

    // A request object passed by value as a compact JWT.
    if let Some(map) = draft13_decode_jwt_claims(request) {
        return Ok(map);
    }

    Err(Oid4vpFacadeError::RequestParsing(
        "unrecognized draft-13 request shape".into(),
    ))
}

/// If the parameter map only references the real request object (`request` by
/// value or `request_uri` by reference), resolve it into the actual parameters;
/// otherwise the inline parameters are the request.
async fn draft13_resolve_indirection(
    map: Map<String, Value>,
) -> Result<Map<String, Value>, Oid4vpFacadeError> {
    if let Some(Value::String(request_object)) = map.get("request") {
        return draft13_params_from_request_object(request_object).ok_or_else(|| {
            Oid4vpFacadeError::RequestParsing("could not parse `request` object".into())
        });
    }

    if let Some(Value::String(request_uri)) = map.get("request_uri") {
        let body = reqwest::get(request_uri)
            .await
            .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("request_uri fetch: {e}")))?
            .text()
            .await
            .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("request_uri body: {e}")))?;
        return draft13_params_from_request_object(&body).ok_or_else(|| {
            Oid4vpFacadeError::RequestParsing("could not parse fetched request object".into())
        });
    }

    Ok(map)
}

/// Parse a fetched/inline request object that may be a compact JWT or raw JSON.
fn draft13_params_from_request_object(value: &str) -> Option<Map<String, Value>> {
    if let Some(claims) = draft13_decode_jwt_claims(value) {
        return Some(claims);
    }
    match serde_json::from_str::<Value>(value) {
        Ok(Value::Object(map)) => Some(map),
        _ => None,
    }
}

/// Decode the claims of a compact JWS/JWT WITHOUT verifying its signature.
/// Returns `None` if the input is not a 3-segment JWT or the payload is not a
/// JSON object. Logs a warning when a signed (non-`alg:none`) token is decoded,
/// because the draft-13 path does not verify request-object signatures.
fn draft13_decode_jwt_claims(token: &str) -> Option<Map<String, Value>> {
    let parts: Vec<&str> = token.trim().split('.').collect();
    if parts.len() != 3 {
        return None;
    }
    let payload = BASE64_URL_SAFE_NO_PAD.decode(parts[1]).ok()?;
    let claims = match serde_json::from_slice::<Value>(&payload) {
        Ok(Value::Object(map)) => map,
        _ => return None,
    };
    // A non-empty signature segment means the verifier signed the request; the
    // draft-13 path accepts it without verification (see module comment).
    if !parts[2].is_empty() {
        log::warn!(
            "OID4VP draft-13: accepting a signed request object WITHOUT signature \
             verification (the draft-13 compatibility path does not verify request \
             object signatures)"
        );
    }
    Some(claims)
}

/// Apply the two draft-13 → draft-18 edits in place.
fn draft13_translate_params(map: &mut Map<String, Value>) -> Result<(), Oid4vpFacadeError> {
    // `post` (or absent, for the cross-device flow this path serves) → `direct_post`.
    map.insert("response_mode".into(), Value::String("direct_post".into()));

    // Move the response destination: draft 13 uses `redirect_uri`, the draft-18
    // engine delivers `direct_post` responses to `response_uri` (and rejects a
    // request carrying both). Accept the `redirect_uris` array form from the
    // §7.2 example as a fallback.
    if !map.contains_key("response_uri") {
        let redirect = map
            .remove("redirect_uri")
            .or_else(|| {
                // `redirect_uris` array form (§7.2 example): take the first entry.
                map.remove("redirect_uris").map(|v| match v {
                    Value::Array(mut a) if !a.is_empty() => a.remove(0),
                    other => other,
                })
            })
            .ok_or_else(|| {
                Oid4vpFacadeError::RequestParsing(
                    "draft-13 request has neither redirect_uri nor response_uri".into(),
                )
            })?;
        map.insert("response_uri".into(), redirect);
    } else {
        map.remove("redirect_uri");
        map.remove("redirect_uris");
    }

    // `response_type` is REQUIRED by the draft-18 object parser; draft-13
    // requests carry `vp_token`, but default it if a request object omitted it.
    map.entry("response_type")
        .or_insert_with(|| Value::String("vp_token".into()));

    Ok(())
}

/// Turn a URL query-parameter value into JSON: object/array-valued params
/// (`presentation_definition`, `client_metadata`, …) arrive percent-decoded as
/// JSON text and must become real JSON; everything else stays a string (so a
/// numeric-looking `nonce`/`state` is not coerced to a JSON number).
fn draft13_value(raw: &str) -> Value {
    let trimmed = raw.trim_start();
    if trimmed.starts_with('{') || trimmed.starts_with('[') {
        serde_json::from_str(raw).unwrap_or_else(|_| Value::String(raw.to_string()))
    } else {
        Value::String(raw.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::context::default_ld_json_context;
    use crate::credential::json_vc::JsonVc;
    use crate::did::DidMethod;
    use crate::tests::load_jwk;
    use serde_json::json;
    use ssi::claims::jws::JwsSigner;
    use ssi::JWK;

    #[derive(Debug)]
    struct TestSigner {
        jwk: JWK,
    }

    #[async_trait::async_trait]
    impl Oid4vpPresentationSigner for TestSigner {
        // The test signer holds a single key, so `key_id` is ignored.
        async fn sign(
            &self,
            _key_id: String,
            payload: Vec<u8>,
        ) -> Result<Vec<u8>, Oid4vpFacadeError> {
            let sig = self
                .jwk
                .sign_bytes(&payload)
                .await
                .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("{e:?}")))?;

            Ok(p256::ecdsa::Signature::from_slice(&sig)
                .map(|sig| sig.to_der().as_bytes().to_vec())
                .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("{e:?}")))?)
        }

        fn algorithm(&self) -> Algorithm {
            self.jwk
                .algorithm
                .map(Algorithm::from)
                .unwrap_or(Algorithm::ES256)
        }

        async fn verification_method(&self, _key_id: String) -> String {
            DidMethod::Key
                .vm_from_jwk(&self.jwk(String::new()))
                .await
                .unwrap()
                .id
                .to_string()
        }

        fn did(&self, _key_id: String) -> String {
            DidMethod::Key
                .did_from_jwk(&self.jwk(String::new()))
                .unwrap()
                .to_string()
        }

        fn cryptosuite(&self) -> CryptosuiteString {
            CryptosuiteString::new("ecdsa-rdfc-2019".to_string()).unwrap()
        }

        fn jwk(&self, _key_id: String) -> String {
            serde_json::to_string(&self.jwk.to_public()).unwrap()
        }
    }

    fn alumni_credential() -> Arc<ParsedCredential> {
        let json_vc =
            JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").to_string())
                .unwrap();
        ParsedCredential::new_ldp_vc(json_vc)
    }

    fn v1_request() -> String {
        json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-123",
            "nonce": "nonce-123",
            "client_metadata": {
                "vp_formats_supported": {
                    "ldp_vc": {
                        "proof_type_values": ["ecdsa-rdfc-2019"]
                    }
                }
            },
            "dcql_query": {
                "credentials": [
                    {
                        "id": "alumni_vc_0",
                        "format": "ldp_vc",
                        "claims": [
                            {
                                "path": ["credentialSubject", "alumniOf", "name"],
                                "intent_to_retain": true
                            }
                        ]
                    }
                ],
                "credential_sets": [
                    {
                        "options": [["alumni_vc_0"]]
                    }
                ]
            }
        })
        .to_string()
    }

    fn draft18_request() -> String {
        json!({
            "client_id": "https://wallet.example/callback",
            "client_id_scheme": "redirect_uri",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-456",
            "nonce": "nonce-456",
            "client_metadata": {
                "vp_formats": {
                    "ldp_vp": {
                        "proof_type": ["ecdsa-rdfc-2019"]
                    }
                }
            },
            "presentation_definition": {
                "id": "pd-alumni",
                "purpose": "Prove university alumni status",
                "input_descriptors": [
                    {
                        "id": "alumni_descriptor",
                        "name": "Alumni Credential",
                        "purpose": "Prove university alumni status",
                        "format": {
                            "ldp_vc": {
                                "proof_type": ["DataIntegrityProof"]
                            }
                        },
                        "constraints": {
                            "fields": [
                                {
                                    "path": ["$.credentialSubject.alumniOf.name"],
                                    "name": "Alumni organization",
                                    "purpose": "Verify alumni relationship",
                                    "intent_to_retain": true
                                }
                            ]
                        }
                    }
                ]
            }
        })
        .to_string()
    }

    /// A draft-13 request object: Presentation Exchange (like draft 18) but with
    /// the bare `post` response mode and `redirect_uri` (no `client_id_scheme`,
    /// no `response_uri`).
    fn draft13_request() -> String {
        json!({
            "client_id": "https://wallet.example/callback",
            "redirect_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "post",
            "state": "state-d13",
            "nonce": "nonce-d13",
            "client_metadata": {
                "vp_formats": {
                    "ldp_vp": {
                        "proof_type": ["ecdsa-rdfc-2019"]
                    }
                }
            },
            "presentation_definition": {
                "id": "pd-alumni",
                "purpose": "Prove university alumni status",
                "input_descriptors": [
                    {
                        "id": "alumni_descriptor",
                        "name": "Alumni Credential",
                        "purpose": "Prove university alumni status",
                        "format": {
                            "ldp_vc": {
                                "proof_type": ["DataIntegrityProof"]
                            }
                        },
                        "constraints": {
                            "fields": [
                                {
                                    "path": ["$.credentialSubject.alumniOf.name"],
                                    "name": "Alumni organization",
                                    "purpose": "Verify alumni relationship",
                                    "intent_to_retain": true
                                }
                            ]
                        }
                    }
                ]
            }
        })
        .to_string()
    }

    fn hybrid_request() -> String {
        json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "client_id_scheme": "redirect_uri",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "state": "state-hybrid",
            "nonce": "nonce-hybrid",
            "client_metadata": {
                "vp_formats": {
                    "ldp_vp": {
                        "proof_type": ["ecdsa-rdfc-2019"]
                    }
                }
            },
            "dcql_query": {
                "credentials": [
                    {
                        "id": "alumni_vc_0",
                        "format": "ldp_vc",
                        "claims": [
                            {
                                "path": ["credentialSubject", "alumniOf", "name"],
                                "intent_to_retain": true
                            }
                        ]
                    }
                ],
                "credential_sets": [
                    {
                        "options": [["alumni_vc_0"]]
                    }
                ]
            },
            "presentation_definition": {
                "id": "pd-alumni",
                "purpose": "Prove university alumni status",
                "input_descriptors": [
                    {
                        "id": "alumni_descriptor",
                        "name": "Alumni Credential",
                        "purpose": "Prove university alumni status",
                        "format": {
                            "ldp_vc": {
                                "proof_type": ["DataIntegrityProof"]
                            }
                        },
                        "constraints": {
                            "fields": [
                                {
                                    "path": ["$.credentialSubject.alumniOf.name"],
                                    "name": "Alumni organization",
                                    "purpose": "Verify alumni relationship",
                                    "intent_to_retain": true
                                }
                            ]
                        }
                    }
                ]
            }
        })
        .to_string()
    }

    #[test]
    fn facade_wraps_v1_presentable_credentials() {
        let parsed = alumni_credential();
        let credential = Arc::new(PresentableCredential {
            inner: parsed.inner.clone(),
            selected_fields: None,
            credential_query_id: "employee".into(),
        });

        let wrapped = Oid4vpPresentableCredential::from_v1(credential);
        assert_eq!(wrapped.match_id(), "employee");
        assert_eq!(wrapped.version(), Oid4vpVersion::V1);
        assert!(!wrapped.selective_disclosable());
    }

    #[test]
    fn facade_wraps_draft18_presentable_credentials() {
        let parsed = alumni_credential();
        let credential = Arc::new(Draft18PresentableCredential {
            inner: parsed.inner.clone(),
            limit_disclosure: false,
            selected_fields: None,
            input_descriptor_id: "age_over_18".into(),
        });

        let wrapped = Oid4vpPresentableCredential::from_draft18(credential);
        assert_eq!(wrapped.match_id(), "age_over_18");
        assert_eq!(wrapped.version(), Oid4vpVersion::Draft18);
        assert!(!wrapped.selective_disclosable());
    }

    #[tokio::test]
    async fn facade_holder_rejects_unsupported_requests() {
        let holder = Oid4vpHolder::new_with_credentials(
            Vec::new(),
            Vec::new(),
            Box::new(TestSigner {
                jwk: JWK::generate_p256(),
            }),
            Default::default(),
            String::new(),
            None,
            None,
        )
        .await
        .unwrap();

        let err = holder
            .start("openid4vp://?client_id=test&nonce=123".into())
            .await;
        assert!(matches!(err, Err(Oid4vpFacadeError::UnsupportedRequest)));
    }

    #[tokio::test]
    async fn facade_holder_can_force_v1_mode() {
        let credential = alumni_credential();
        let holder = Oid4vpHolder::new_with_credentials(
            vec![credential],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder
            .start_with_supported_versions(hybrid_request(), vec![Oid4vpVersion::V1])
            .await
            .unwrap();

        assert_eq!(session.version(), Oid4vpVersion::V1);
    }

    #[tokio::test]
    async fn facade_holder_can_force_draft18_mode() {
        let credential = alumni_credential();
        let holder = Oid4vpHolder::new_with_credentials(
            vec![credential],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder
            .start_with_supported_versions(hybrid_request(), vec![Oid4vpVersion::Draft18])
            .await
            .unwrap();

        assert_eq!(session.version(), Oid4vpVersion::Draft18);
    }

    #[tokio::test]
    async fn facade_v1_flow_creates_permission_response() {
        let credential = alumni_credential();
        let holder = Oid4vpHolder::new_with_credentials(
            vec![credential.clone()],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder.start(v1_request()).await.unwrap();

        assert_eq!(session.version(), Oid4vpVersion::V1);
        assert_eq!(
            session.client_id().as_deref(),
            Some("redirect_uri:https://wallet.example/callback")
        );
        assert_eq!(session.requirements().len(), 1);
        assert!(!session.is_multi_credential_selection());
        assert!(!session.is_multi_credential_matching());

        let requirement = session.requirements().pop().unwrap();
        assert_eq!(requirement.id, "alumni_vc_0");
        assert_eq!(requirement.credentials.len(), 1);

        let requested_fields = session
            .requested_fields(requirement.credentials.first().unwrap())
            .unwrap();
        assert_eq!(requested_fields.len(), 1);
        assert_eq!(requested_fields[0].match_id, "alumni_vc_0");
        assert!(requested_fields[0].raw_fields.is_empty());

        let response = session
            .create_permission_response(
                requirement.credentials.clone(),
                vec![requested_fields
                    .iter()
                    .map(|field| field.path.clone())
                    .collect()],
                Oid4vpResponseOptions::default(),
            )
            .await
            .unwrap();

        assert_eq!(response.version(), Oid4vpVersion::V1);
        assert_eq!(response.selected_credentials().len(), 1);
        let vp_token = response.vp_token().unwrap();
        assert!(vp_token.contains("alumni_vc_0"));
        assert!(vp_token.contains("AlumniCredential"));
    }

    #[tokio::test]
    async fn facade_draft18_flow_creates_permission_response() {
        let credential = alumni_credential();
        let holder = Oid4vpHolder::new_with_credentials(
            vec![credential.clone()],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder.start(draft18_request()).await.unwrap();

        assert_eq!(session.version(), Oid4vpVersion::Draft18);
        assert_eq!(
            session.client_id().as_deref(),
            Some("https://wallet.example/callback")
        );
        assert_eq!(
            session.purpose().as_deref(),
            Some("Prove university alumni status")
        );
        assert_eq!(session.requirements().len(), 1);
        assert!(!session.is_multi_credential_selection());
        assert!(!session.is_multi_credential_matching());

        let requirement = session.requirements().pop().unwrap();
        assert_eq!(requirement.id, "alumni_descriptor");
        assert_eq!(requirement.display_name, "Alumni Credential");
        assert_eq!(requirement.credentials.len(), 1);
        assert_eq!(
            requirement.credentials[0].match_id(),
            "alumni_descriptor".to_string()
        );

        let requested_fields = session
            .requested_fields(requirement.credentials.first().unwrap())
            .unwrap();
        assert_eq!(requested_fields.len(), 1);
        assert_eq!(requested_fields[0].match_id, "alumni_descriptor");
        assert!(!requested_fields[0].raw_fields.is_empty());

        let response = session
            .create_permission_response(
                requirement.credentials.clone(),
                vec![requested_fields
                    .iter()
                    .map(|field| field.path.clone())
                    .collect()],
                Oid4vpResponseOptions::default(),
            )
            .await
            .unwrap();

        assert_eq!(response.version(), Oid4vpVersion::Draft18);
        assert_eq!(response.selected_credentials().len(), 1);
        let vp_token = response.vp_token().unwrap();
        assert!(vp_token.contains("AlumniCredential"));
        assert!(vp_token.contains("Example University"));
    }

    #[tokio::test]
    async fn facade_auto_detects_draft13_by_post_response_mode() {
        let credential = alumni_credential();
        let holder = Oid4vpHolder::new_with_credentials(
            vec![credential],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        // Auto mode must classify a `response_mode=post` PE request as draft 13,
        // not draft 18.
        let session = holder.start(draft13_request()).await.unwrap();
        assert_eq!(session.version(), Oid4vpVersion::Draft13);
    }

    #[tokio::test]
    async fn facade_draft13_flow_creates_permission_response() {
        let credential = alumni_credential();
        let holder = Oid4vpHolder::new_with_credentials(
            vec![credential.clone()],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        // The draft-13 request flows through the translated draft-18 engine: same
        // PE matching, requirements, requested fields, and vp_token — the session
        // simply reports the draft-13 version.
        let session = holder
            .start_with_supported_versions(draft13_request(), vec![Oid4vpVersion::Draft13])
            .await
            .unwrap();

        assert_eq!(session.version(), Oid4vpVersion::Draft13);
        assert_eq!(
            session.purpose().as_deref(),
            Some("Prove university alumni status")
        );
        assert_eq!(session.requirements().len(), 1);

        let requirement = session.requirements().pop().unwrap();
        assert_eq!(requirement.id, "alumni_descriptor");
        assert_eq!(requirement.credentials.len(), 1);

        let requested_fields = session
            .requested_fields(requirement.credentials.first().unwrap())
            .unwrap();
        assert_eq!(requested_fields.len(), 1);
        assert_eq!(requested_fields[0].match_id, "alumni_descriptor");

        let response = session
            .create_permission_response(
                requirement.credentials.clone(),
                vec![requested_fields
                    .iter()
                    .map(|field| field.path.clone())
                    .collect()],
                Oid4vpResponseOptions::default(),
            )
            .await
            .unwrap();

        assert_eq!(response.selected_credentials().len(), 1);
        let vp_token = response.vp_token().unwrap();
        assert!(vp_token.contains("AlumniCredential"));
        assert!(vp_token.contains("Example University"));
    }

    #[test]
    fn draft13_translation_moves_redirect_uri_and_response_mode() {
        // Unit-test the core translation: post -> direct_post, redirect_uri ->
        // response_uri (the two edits that let the draft-18 engine serve draft 13).
        let mut params: Map<String, Value> = serde_json::from_str(
            r#"{"client_id":"https://verifier.example/cb","redirect_uri":"https://verifier.example/cb","response_type":"vp_token","response_mode":"post","nonce":"n1"}"#,
        )
        .unwrap();

        draft13_translate_params(&mut params).unwrap();

        assert_eq!(params.get("response_mode").unwrap(), "direct_post");
        assert_eq!(
            params.get("response_uri").unwrap(),
            "https://verifier.example/cb"
        );
        assert!(params.get("redirect_uri").is_none());

        // And the translated object must parse as a draft-18 request object
        // (i.e. it satisfies the crate's response_uri-required-for-direct_post rule).
        let obj: Result<
            openidvp_draft18::core::authorization_request::AuthorizationRequestObject,
            _,
        > = serde_json::from_value(Value::Object(params));
        assert!(
            obj.is_ok(),
            "translated draft-13 must be a valid draft-18 object"
        );
    }

    #[test]
    fn draft13_translation_requires_a_response_destination() {
        let mut params: Map<String, Value> = serde_json::from_str(
            r#"{"client_id":"https://verifier.example/cb","response_type":"vp_token","response_mode":"post","nonce":"n1"}"#,
        )
        .unwrap();
        assert!(matches!(
            draft13_translate_params(&mut params),
            Err(Oid4vpFacadeError::RequestParsing(_))
        ));
    }

    #[test]
    fn draft13_translation_accepts_redirect_uris_array_form() {
        // §7.2's non-normative example uses `redirect_uris` as an ARRAY; the
        // first entry becomes the response destination.
        let mut params: Map<String, Value> = serde_json::from_str(
            r#"{"client_id":"https://client.example.org/post","redirect_uris":["https://client.example.org/post"],"response_type":"vp_token","response_mode":"post","nonce":"n1"}"#,
        )
        .unwrap();

        draft13_translate_params(&mut params).unwrap();

        assert_eq!(
            params.get("response_uri").unwrap(),
            "https://client.example.org/post"
        );
        assert!(params.get("redirect_uris").is_none());
        assert!(params.get("redirect_uri").is_none());
        // Round-trips into a valid draft-18 object.
        let obj: Result<
            openidvp_draft18::core::authorization_request::AuthorizationRequestObject,
            _,
        > = serde_json::from_value(Value::Object(params));
        assert!(obj.is_ok());
    }

    #[test]
    fn draft13_translation_strips_redirect_uri_when_response_uri_present() {
        // If a request unusually already carries `response_uri`, redirect_uri(s)
        // must be removed so the translated object does not carry BOTH (the
        // draft-18 parser rejects that as mutually exclusive).
        let mut params: Map<String, Value> = serde_json::from_str(
            r#"{"client_id":"https://verifier.example/cb","response_uri":"https://verifier.example/resp","redirect_uri":"https://verifier.example/cb","response_type":"vp_token","response_mode":"post","nonce":"n1"}"#,
        )
        .unwrap();

        draft13_translate_params(&mut params).unwrap();

        assert_eq!(
            params.get("response_uri").unwrap(),
            "https://verifier.example/resp"
        );
        assert!(params.get("redirect_uri").is_none());
        let obj: Result<
            openidvp_draft18::core::authorization_request::AuthorizationRequestObject,
            _,
        > = serde_json::from_value(Value::Object(params));
        assert!(
            obj.is_ok(),
            "must not fail the draft-18 mutual-exclusivity rule"
        );
    }

    #[test]
    fn draft13_translation_defaults_missing_response_type() {
        // A request object that omits response_type still parses (defaulted to
        // vp_token, which the draft-18 object parser requires).
        let mut params: Map<String, Value> = serde_json::from_str(
            r#"{"client_id":"https://verifier.example/cb","redirect_uri":"https://verifier.example/cb","response_mode":"post","nonce":"n1"}"#,
        )
        .unwrap();
        draft13_translate_params(&mut params).unwrap();
        assert_eq!(params.get("response_type").unwrap(), "vp_token");
    }

    #[test]
    fn draft13_decode_jwt_claims_decodes_object_payload_without_verification() {
        // A 3-segment compact JWS whose payload is a JSON object decodes to its
        // claims (signature NOT verified — draft-13 path accepts unsigned/signed).
        let payload = BASE64_URL_SAFE_NO_PAD
            .encode(br#"{"client_id":"https://verifier.example/cb","response_mode":"post"}"#);
        let token = format!("eyJhbGciOiJFUzI1NiJ9.{payload}.c2ln");
        let claims = draft13_decode_jwt_claims(&token).expect("decodes 3-part JWT");
        assert_eq!(claims.get("response_mode").unwrap(), "post");

        // Not a 3-segment token → None.
        assert!(draft13_decode_jwt_claims("only.two").is_none());
        assert!(draft13_decode_jwt_claims("not-a-jwt").is_none());

        // 3 segments but the payload is not a JSON object → None.
        let scalar = BASE64_URL_SAFE_NO_PAD.encode(b"\"just-a-string\"");
        assert!(draft13_decode_jwt_claims(&format!("h.{scalar}.s")).is_none());
    }

    #[test]
    fn draft13_params_from_request_object_dispatches_jwt_and_json() {
        // Raw JSON object request.
        let json = r#"{"client_id":"https://verifier.example/cb","response_mode":"post"}"#;
        let from_json = draft13_params_from_request_object(json).expect("parses JSON object");
        assert_eq!(from_json.get("response_mode").unwrap(), "post");

        // Compact-JWT request object (same claims).
        let payload = BASE64_URL_SAFE_NO_PAD.encode(json.as_bytes());
        let jwt = format!("eyJhbGciOiJub25lIn0.{payload}.");
        let from_jwt = draft13_params_from_request_object(&jwt).expect("parses JWT claims");
        assert_eq!(
            from_jwt.get("client_id").unwrap(),
            "https://verifier.example/cb"
        );
    }

    #[test]
    fn draft13_value_coerces_json_objects_but_keeps_scalars_as_strings() {
        // Object/array values (presentation_definition) become real JSON…
        assert!(draft13_value(r#"{"id":"pd"}"#).is_object());
        assert!(draft13_value(r#"["a","b"]"#).is_array());
        // …while scalars (incl. numeric-looking nonce/state) stay strings so the
        // draft-18 typed parameters (which expect strings) don't choke.
        assert_eq!(draft13_value("12345"), Value::String("12345".into()));
        assert_eq!(
            draft13_value("https://verifier.example/cb"),
            Value::String("https://verifier.example/cb".into())
        );
    }

    /// End-to-end proof that the draft-13 path's SUBMITTED response is the
    /// draft-13 §7.2 wire shape: a form-encoded POST of `vp_token` +
    /// `presentation_submission` to the draft-13 `redirect_uri`, with the
    /// single-VP descriptor_map root path `$` (§6.2 / §A.2.2). This is the one
    /// assertion the create-only test cannot make — it inspects the actual bytes.
    #[tokio::test]
    async fn facade_draft13_submits_post_response_to_redirect_uri() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        // The verifier's draft-13 redirect_uri (= where the `post` response lands).
        Mock::given(method("POST"))
            .and(path("/cb"))
            .respond_with(ResponseTemplate::new(200))
            .expect(1)
            .mount(&server)
            .await;
        let redirect_uri = format!("{}/cb", server.uri());

        let request = json!({
            "client_id": redirect_uri,
            "redirect_uri": redirect_uri,
            "response_type": "vp_token",
            "response_mode": "post",
            "nonce": "nonce-d13",
            "client_metadata": {
                "vp_formats": {
                    "ldp_vp": { "proof_type": ["ecdsa-rdfc-2019"] }
                }
            },
            "presentation_definition": {
                "id": "pd-alumni",
                "input_descriptors": [{
                    "id": "alumni_descriptor",
                    "format": { "ldp_vc": { "proof_type": ["DataIntegrityProof"] } },
                    "constraints": {
                        "fields": [{ "path": ["$.credentialSubject.alumniOf.name"] }]
                    }
                }]
            }
        })
        .to_string();

        let holder = Oid4vpHolder::new_with_credentials(
            vec![alumni_credential()],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder
            .start_with_supported_versions(request, vec![Oid4vpVersion::Draft13])
            .await
            .unwrap();
        assert_eq!(session.version(), Oid4vpVersion::Draft13);

        let requirement = session.requirements().pop().unwrap();
        let requested_fields = session
            .requested_fields(requirement.credentials.first().unwrap())
            .unwrap();
        let response = session
            .create_permission_response(
                requirement.credentials.clone(),
                vec![requested_fields.iter().map(|f| f.path.clone()).collect()],
                Oid4vpResponseOptions::default(),
            )
            .await
            .unwrap();

        // Submit drives the draft-18 engine's POST to return_uri (= redirect_uri).
        session.submit_permission_response(response).await.unwrap();

        // Inspect the actual bytes the verifier received.
        let received = server.received_requests().await.unwrap();
        assert_eq!(received.len(), 1, "exactly one POST to the redirect_uri");
        let body = String::from_utf8(received[0].body.clone()).unwrap();
        let decoded = urlencoding::decode(&body).unwrap().into_owned();

        assert!(
            body.contains("vp_token=") && body.contains("presentation_submission="),
            "§7.2 response carries both form params, got: {body}"
        );
        // §6.2 / §A.2.2: single-VP descriptor_map root path is `$`.
        assert!(
            decoded.contains("\"descriptor_map\""),
            "presentation_submission present, got: {decoded}"
        );
        assert!(
            decoded.contains("\"path\":\"$\""),
            "single-VP descriptor_map root path must be \"$\" (draft-13 §6.2), got: {decoded}"
        );
    }

    /// Regression for the motivating bug: a draft-13 request delivered by a bare
    /// `request_uri` (no `response_mode` in the outer link) is misclassified as
    /// draft 18 under auto, whose fetch then burns the single-use `request_uri`
    /// before failing. Excluding draft 18 from the supported set routes it to the
    /// draft-13 flow, which fetches the `request_uri` exactly once.
    #[tokio::test]
    async fn facade_bare_request_uri_resolves_to_draft13_when_draft18_excluded() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        let redirect_uri = format!("{}/cb", server.uri());

        // The draft-13 request object served behind the request_uri. Raw JSON,
        // `response_mode=post` — the discriminator lives here, behind the fetch.
        let request_object = json!({
            "client_id": redirect_uri,
            "redirect_uri": redirect_uri,
            "response_type": "vp_token",
            "response_mode": "post",
            "nonce": "nonce-d13",
            "client_metadata": {
                "vp_formats": { "ldp_vp": { "proof_type": ["ecdsa-rdfc-2019"] } }
            },
            "presentation_definition": {
                "id": "pd-alumni",
                "input_descriptors": [{
                    "id": "alumni_descriptor",
                    "format": { "ldp_vc": { "proof_type": ["DataIntegrityProof"] } },
                    "constraints": {
                        "fields": [{ "path": ["$.credentialSubject.alumniOf.name"] }]
                    }
                }]
            }
        })
        .to_string();

        Mock::given(method("GET"))
            .and(path("/request"))
            .respond_with(ResponseTemplate::new(200).set_body_string(request_object))
            .expect(1)
            .mount(&server)
            .await;

        // The outer link carries only a bare request_uri: the version cannot be
        // told from it without fetching.
        let link = format!(
            "openid4vp://?client_id={}&request_uri={}",
            urlencoding::encode(&redirect_uri),
            urlencoding::encode(&format!("{}/request", server.uri()))
        );

        let holder = Oid4vpHolder::new_with_credentials(
            vec![alumni_credential()],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder
            .start_with_supported_versions(link, vec![Oid4vpVersion::V1, Oid4vpVersion::Draft13])
            .await
            .unwrap();

        assert_eq!(session.version(), Oid4vpVersion::Draft13);

        let received = server.received_requests().await.unwrap();
        assert_eq!(
            received
                .iter()
                .filter(|r| r.url.path() == "/request")
                .count(),
            1,
            "the request_uri must be fetched exactly once, by the draft-13 flow"
        );
    }

    /// Draft 13 and Draft 18 share a request shape that is only separable after
    /// the single-use `request_uri` fetch, so supporting both at once is rejected
    /// up front rather than risking a wrong-version fetch.
    #[tokio::test]
    async fn facade_rejects_draft13_and_draft18_together() {
        let holder = Oid4vpHolder::new_with_credentials(
            vec![alumni_credential()],
            Vec::new(),
            Box::new(TestSigner { jwk: load_jwk() }),
            Default::default(),
            String::new(),
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let result = holder
            .start_with_supported_versions(
                draft13_request(),
                vec![Oid4vpVersion::Draft13, Oid4vpVersion::Draft18],
            )
            .await;

        assert!(matches!(
            result,
            Err(Oid4vpFacadeError::ConflictingVersions)
        ));
    }
}
