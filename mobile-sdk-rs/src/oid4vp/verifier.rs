use crate::common::Url;

use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Oid4vpVerifierError {
    #[error("HTTP client error: {0}")]
    HttpClient(String),
    #[error("Invalid URL: {0}")]
    Url(String),
}

#[derive(Debug, uniffi::Object)]
pub struct DelegatedVerifier {
    base_url: Url,

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
    pub auth_query: String,
    /// This is the status URL to check the presentation status
    /// from the delegated verifier.
    pub uri: String,
}

#[uniffi::export(async_runtime = "tokio")]
impl DelegatedVerifier {
    #[uniffi::constructor]
    pub async fn new_client(base_url: Url) -> Result<Arc<Self>, Oid4vpVerifierError> {
        let client = openid4vp::core::util::ReqwestClient::new()
            .map_err(|e| Oid4vpVerifierError::HttpClient(format!("{e:?}")))?;

        Ok(Arc::new(Self { base_url, client }))
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
        url: &str,
    ) -> Result<DelegateInitializationResponse, Oid4vpVerifierError> {
        let uri = self
            .base_url
            .join(url)
            .map_err(|e| Oid4vpVerifierError::Url(format!("{e:?}")))?;

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

    pub async fn poll_verification_status(
        &self,
        url: &str,
    ) -> Result<DelegatedVerifierStatusResponse, Oid4vpVerifierError> {
        let uri = self
            .base_url
            .join(url)
            .map_err(|e| Oid4vpVerifierError::Url(format!("{e:?}")))?;

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

#[cfg(test)]
mod tests {
    use super::*;

    // NOTE: This requires an instance of credible to be accessible
    const BASE_URL: &str = "http://localhost:3003";
    const DELEGATED_VERIFIER_URL: &str = "/api2/verifier/1/delegate";

    #[tokio::test]
    async fn test_delegated_verification() -> Result<(), Oid4vpVerifierError> {
        let verifier =
            DelegatedVerifier::new_client(BASE_URL.parse().expect("Failed to parse Base URL"))
                .await
                .expect("Failed to create verifier");

        let DelegateInitializationResponse { auth_query, uri } = verifier
            .request_delegated_verification(DELEGATED_VERIFIER_URL)
            .await
            .expect("Failed to request delegated verification");

        println!("Auth Query: {}", auth_query);
        println!("URI: {}", uri);

        let status = verifier.poll_verification_status(&uri).await?;

        println!("Status: {:?}", status);

        Ok(())
    }
}
