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
use super::{get_oid4vp_version, Oid4vpVersion};
use crate::oid4vp::draft18::error::Draft18OID4VPError;
use crate::oid4vp::error::OID4VPError;
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
#[derive(uniffi::Enum, Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Oid4vpCompatibilityMode {
    #[default]
    Auto,
    V1,
    Draft18,
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
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, Oid4vpFacadeError>;
    fn algorithm(&self) -> Algorithm;
    async fn verification_method(&self) -> String;
    fn did(&self) -> String;
    fn cryptosuite(&self) -> CryptosuiteString;
    fn jwk(&self) -> String;
}

#[derive(Debug)]
struct V1SignerAdapter {
    signer: Arc<Box<dyn Oid4vpPresentationSigner>>,
}

#[async_trait::async_trait]
impl PresentationSigner for V1SignerAdapter {
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, PresentationError> {
        self.signer
            .sign(payload)
            .await
            .map_err(|e| PresentationError::Signing(e.to_string()))
    }

    fn algorithm(&self) -> Algorithm {
        self.signer.algorithm()
    }

    async fn verification_method(&self) -> String {
        self.signer.verification_method().await
    }

    fn did(&self) -> String {
        self.signer.did()
    }

    fn cryptosuite(&self) -> CryptosuiteString {
        self.signer.cryptosuite()
    }

    fn jwk(&self) -> String {
        self.signer.jwk()
    }
}

#[derive(Debug)]
struct Draft18SignerAdapter {
    signer: Arc<Box<dyn Oid4vpPresentationSigner>>,
}

#[async_trait::async_trait]
impl Draft18PresentationSigner for Draft18SignerAdapter {
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, Draft18PresentationError> {
        self.signer
            .sign(payload)
            .await
            .map_err(|e| Draft18PresentationError::Signing(e.to_string()))
    }

    fn algorithm(&self) -> Algorithm {
        self.signer.algorithm()
    }

    async fn verification_method(&self) -> String {
        self.signer.verification_method().await
    }

    fn did(&self) -> String {
        self.signer.did()
    }

    fn cryptosuite(&self) -> CryptosuiteString {
        self.signer.cryptosuite()
    }

    fn jwk(&self) -> String {
        self.signer.jwk()
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
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, Oid4vpFacadeError> {
        Ok(Arc::new(Self {
            source: Oid4vpHolderSource::Collection(vdc_collection),
            trusted_dids,
            signer: Arc::new(signer),
            context_map,
            keystore,
        }))
    }

    #[uniffi::constructor]
    pub async fn new_with_credentials(
        provided_credentials: Vec<Arc<ParsedCredential>>,
        trusted_dids: Vec<String>,
        signer: Box<dyn Oid4vpPresentationSigner>,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, Oid4vpFacadeError> {
        Ok(Arc::new(Self {
            source: Oid4vpHolderSource::Credentials(provided_credentials),
            trusted_dids,
            signer: Arc::new(signer),
            context_map,
            keystore,
        }))
    }

    pub async fn start(&self, request: String) -> Result<Arc<Oid4vpSession>, Oid4vpFacadeError> {
        self.start_with_compatibility_mode(request, Oid4vpCompatibilityMode::Auto)
            .await
    }

    pub async fn start_with_compatibility_mode(
        &self,
        request: String,
        compatibility_mode: Oid4vpCompatibilityMode,
    ) -> Result<Arc<Oid4vpSession>, Oid4vpFacadeError> {
        let version = match compatibility_mode {
            Oid4vpCompatibilityMode::Auto => get_oid4vp_version(request.clone()),
            Oid4vpCompatibilityMode::V1 => Oid4vpVersion::V1,
            Oid4vpCompatibilityMode::Draft18 => Oid4vpVersion::Draft18,
        };

        match version {
            Oid4vpVersion::V1 => {
                let holder = self.new_v1_holder().await?;
                let permission_request = holder
                    .authorization_request(parse_v1_auth_request(&request)?)
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
                    .authorization_request(parse_draft18_auth_request(&request)?)
                    .await?;

                Ok(Arc::new(Oid4vpSession {
                    inner: Oid4vpSessionInner::Draft18 {
                        holder,
                        request: permission_request,
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
                self.context_map.clone(),
                self.keystore.clone(),
            )
            .await
            .map_err(Into::into),
            Oid4vpHolderSource::Credentials(credentials) => Holder::new_with_credentials(
                credentials.clone(),
                self.trusted_dids.clone(),
                signer,
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
                self.context_map.clone(),
            )
            .await
            .map_err(Into::into),
            Oid4vpHolderSource::Credentials(credentials) => Draft18Holder::new_with_credentials(
                credentials.clone(),
                self.trusted_dids.clone(),
                signer,
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
            Oid4vpSessionInner::Draft18 { .. } => Oid4vpVersion::Draft18,
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
        async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, Oid4vpFacadeError> {
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

        async fn verification_method(&self) -> String {
            DidMethod::Key
                .vm_from_jwk(&self.jwk())
                .await
                .unwrap()
                .id
                .to_string()
        }

        fn did(&self) -> String {
            DidMethod::Key
                .did_from_jwk(&self.jwk())
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
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder
            .start_with_compatibility_mode(hybrid_request(), Oid4vpCompatibilityMode::V1)
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
            Some(default_ld_json_context()),
            None,
        )
        .await
        .unwrap();

        let session = holder
            .start_with_compatibility_mode(hybrid_request(), Oid4vpCompatibilityMode::Draft18)
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
}
