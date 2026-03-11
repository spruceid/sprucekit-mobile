use serde::{Deserialize, Serialize};
use std::sync::Arc;
use url::Url;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Draft18Oid4vpVerifierError {
    #[error("HTTP client error: {0}")]
    HttpClient(String),
    #[error("Invalid URL: {0}")]
    Url(String),
}

#[derive(Debug, uniffi::Object)]
pub struct Draft18DelegatedVerifier {
    base_url: Url,
    /// HTTP Request Client
    pub(crate) client: openidvp_draft18::core::util::ReqwestClient,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Enum, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Draft18DelegatedVerifierStatus {
    Initiated,
    Pending,
    Failure,
    Success,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Record)]
pub struct Draft18DelegatedVerifierStatusResponse {
    /// The status of the verification request.
    pub status: Draft18DelegatedVerifierStatus,
    /// OID4VP presentation
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oid4vp: Option<Draft18DelegatedVerifierOid4vpResponse>,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Record)]
pub struct Draft18DelegatedVerifierOid4vpResponse {
    /// Presented SD-JWT.
    pub vp_token: String,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Record)]
pub struct Draft18DelegateInitializationResponse {
    /// This is the authorization request URL to be presented in
    /// a QR code to the holder.
    pub auth_query: String,
    /// This is the status URL to check the presentation status
    /// from the delegated verifier.
    pub uri: String,
}

#[uniffi::export(async_runtime = "tokio")]
impl Draft18DelegatedVerifier {
    #[uniffi::constructor]
    pub async fn new_client(base_url: Url) -> Result<Arc<Self>, Draft18Oid4vpVerifierError> {
        let client = openidvp_draft18::core::util::ReqwestClient::new()
            .map_err(|e| Draft18Oid4vpVerifierError::HttpClient(format!("{e:?}")))?;

        Ok(Arc::new(Self { base_url, client }))
    }

    /// Initialize a delegated verification request.
    ///
    /// This method will respond with a uniffi::Record object that contains the
    /// `auth_query` to be presented via QR code to the holder, and a `uri` to
    /// check the status of the presentation from the delegated verifier.
    ///
    /// Provide the `uri` to the [Draft18DelegatedVerifier::poll_verification_status] method to
    /// check the status of the presentation.
    pub async fn request_delegated_verification(
        &self,
        url: &str,
    ) -> Result<Draft18DelegateInitializationResponse, Draft18Oid4vpVerifierError> {
        let uri = self
            .base_url
            .join(url)
            .map_err(|e| Draft18Oid4vpVerifierError::Url(format!("{e:?}")))?;

        self.client
            .as_ref()
            .get(uri)
            .send()
            .await
            .map_err(|e| Draft18Oid4vpVerifierError::HttpClient(format!("{e:?}")))?
            .json()
            .await
            .map_err(|e| Draft18Oid4vpVerifierError::HttpClient(format!("{e:?}")))
    }

    pub async fn poll_verification_status(
        &self,
        url: &str,
    ) -> Result<Draft18DelegatedVerifierStatusResponse, Draft18Oid4vpVerifierError> {
        let uri = self
            .base_url
            .join(url)
            .map_err(|e| Draft18Oid4vpVerifierError::Url(format!("{e:?}")))?;

        self.client
            .as_ref()
            .get(uri)
            .send()
            .await
            .map_err(|e| Draft18Oid4vpVerifierError::HttpClient(format!("{e:?}")))?
            .json()
            .await
            .map_err(|e| Draft18Oid4vpVerifierError::HttpClient(format!("{e:?}")))
    }
}
