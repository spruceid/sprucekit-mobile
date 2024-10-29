use crate::common::Url;

use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Oid4vpVerifierError {
    #[error("HTTP client error: {0}")]
    HttpClient(String),
}

#[derive(Debug, uniffi::Object)]
pub struct DelegatedVerifier {
    /// HTTP Request Client
    pub(crate) client: openid4vp::core::util::ReqwestClient,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum DelegatedVerifierStatus {
    Initiated,
    Pending,
    Failed,
    Success,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Record)]
pub struct DelegatedVerifierStatusResponse {
    /// The status of the verification request.
    pub status: DelegatedVerifierStatus,
    /// JSON-encoded string of the presentation
    #[serde(skip_serializing_if = "Option::is_none")]
    pub presentation: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Record)]
pub struct DelegateInitializationResponse {
    /// This is the authorization request URL to be presented in
    /// a QR code to the holder.
    pub auth_query: Url,
    /// This is the status URL to check the presentation status
    /// from the delegated verifier.
    pub uri: Url,
}

#[uniffi::export(async_runtime = "tokio")]
impl DelegatedVerifier {
    #[uniffi::constructor]
    pub async fn new_client() -> Result<Arc<Self>, Oid4vpVerifierError> {
        let client = openid4vp::core::util::ReqwestClient::new()
            .map_err(|e| Oid4vpVerifierError::HttpClient(format!("{e:?}")))?;

        Ok(Arc::new(Self { client }))
    }

    /// Initialize a delegated verification request.
    ///
    /// This method will respond with a uniffi::Record object that contains the
    /// `auth_query` to be presented via QR code to the holder, and a `uri` to
    /// check the status of the presentation from the delegated verifier.
    ///
    /// Provide the `uri` to the [Verifier::poll_verification_status] method to
    /// check the status of the presentation.
    pub async fn request_delegated_verification(
        &self,
        url: Url,
    ) -> Result<DelegateInitializationResponse, Oid4vpVerifierError> {
        self.client
            .as_ref()
            .get(url)
            .send()
            .await
            .map_err(|e| Oid4vpVerifierError::HttpClient(format!("{e:?}")))?
            .json()
            .await
            .map_err(|e| Oid4vpVerifierError::HttpClient(format!("{e:?}")))
    }

    pub async fn poll_verification_status(
        &self,
        uri: Url,
    ) -> Result<DelegatedVerifierStatus, Oid4vpVerifierError> {
        self.client
            .as_ref()
            .get(uri)
            .send()
            .await
            .map_err(|e| Oid4vpVerifierError::HttpClient(format!("{e:?}")))?
            .json()
            .await
            .map_err(|e| Oid4vpVerifierError::HttpClient(format!("{e:?}")))
    }
}
