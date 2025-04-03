use crate::haci::http_client::HaciHttpClient;
use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Represents errors that may occur during issuance operations
#[derive(Error, Debug, uniffi::Error)]
pub enum IssuanceServiceError {
    /// Failed to send the request to the server
    #[error("Failed to send request: {0}")]
    NetworkError(String),

    /// Server returned an error response
    #[error("Server error: {status} - {error_message}")]
    ServerError { status: u16, error_message: String },

    /// Failed to read the response body
    #[error("Failed to read response body: {0}")]
    ResponseError(String),

    /// Invalid wallet attestation
    #[error("Invalid wallet attestation: {0}")]
    InvalidAttestation(String),

    /// Internal error
    #[error("Internal error: {0}")]
    InternalError(String),
}

#[derive(Debug, Serialize, Deserialize)]
struct NewIssuanceResponse {
    id: String,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Record)]
pub struct CheckStatusResponse {
    state: String,
    openid_credential_offer: String,
}

#[derive(uniffi::Object)]
pub struct IssuanceServiceClient {
    client: HaciHttpClient,
    base_url: String,
}

#[uniffi::export(async_runtime = "tokio")]
impl IssuanceServiceClient {
    /// Creates a new IssuanceServiceClient instance
    ///
    /// # Arguments
    /// * `base_url` - The base URL of the issuance service
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Self {
        Self {
            client: HaciHttpClient::new(),
            base_url,
        }
    }

    /// Creates a new issuance request
    ///
    /// # Arguments
    /// * `wallet_attestation` - The wallet attestation JWT
    ///
    /// # Returns
    /// * The issuance ID if successful
    /// * An error if the request fails
    pub async fn new_issuance(
        &self,
        wallet_attestation: String,
    ) -> Result<String, IssuanceServiceError> {
        let url = format!("{}/issuance/new", self.base_url);

        let response = self
            .client
            .get(url)
            .header("OAuth-Client-Attestation", wallet_attestation)
            .send()
            .await
            .map_err(|e| IssuanceServiceError::NetworkError(e.to_string()))?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let error_text = response.text().await.unwrap_or_default();
            return Err(IssuanceServiceError::ServerError {
                status,
                error_message: error_text,
            });
        }

        let status_response: NewIssuanceResponse = response
            .json()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?;

        Ok(status_response.id)
    }

    /// Checks the status of an issuance request
    ///
    /// # Arguments
    /// * `issuance_id` - The ID of the issuance to check
    /// * `wallet_attestation` - The wallet attestation JWT
    ///
    /// # Returns
    /// * The status response containing state and openid_credential_offer if successful
    /// * An error if the request fails
    pub async fn check_status(
        &self,
        issuance_id: String,
        wallet_attestation: String,
    ) -> Result<CheckStatusResponse, IssuanceServiceError> {
        let url = format!("{}/issuance/{}/status", self.base_url, issuance_id);

        let response = self
            .client
            .get(url)
            .header("OAuth-Client-Attestation", wallet_attestation)
            .send()
            .await
            .map_err(|e| IssuanceServiceError::NetworkError(e.to_string()))?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let error_text = response.text().await.unwrap_or_default();
            return Err(IssuanceServiceError::ServerError {
                status,
                error_message: error_text,
            });
        }

        let status_response: CheckStatusResponse = response
            .json()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?;

        Ok(status_response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    async fn setup_mock_server() -> (MockServer, String) {
        let mock_server = MockServer::start().await;
        let base_url = mock_server.uri();
        (mock_server, base_url)
    }

    #[tokio::test]
    async fn test_successful_new_issuance() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let wallet_attestation = "test_attestation".to_string();
        let expected_id = "d94062ab-e659-4b70-8532-b758973c2b40".to_string();

        // Mock successful new issuance response
        Mock::given(method("GET"))
            .and(path("/issuance/new"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "id": expected_id
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.new_issuance(wallet_attestation).await;
        assert!(result.is_ok(), "New issuance should succeed");
        assert_eq!(result.unwrap(), expected_id);
    }

    #[tokio::test]
    async fn test_successful_check_status() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let issuance_id = "5431d6df-63da-4803-a9fc-d92e5c36b9f8".to_string();
        let wallet_attestation = "test_attestation".to_string();

        // Mock successful status check response
        Mock::given(method("GET"))
            .and(path(format!("/issuance/{}/status", issuance_id)))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "state": "ReadyToProvision",
                "openid_credential_offer": "openid_credential_offer"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.check_status(issuance_id, wallet_attestation).await;
        assert!(result.is_ok(), "Status check should succeed");
        let response = result.unwrap();
        assert_eq!(response.state, "ReadyToProvision");
        assert_eq!(response.openid_credential_offer, "openid_credential_offer");
    }

    #[tokio::test]
    async fn test_server_error_new_issuance() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let wallet_attestation = "test_attestation".to_string();

        // Mock server error response
        Mock::given(method("GET"))
            .and(path("/issuance/new"))
            .respond_with(ResponseTemplate::new(500).set_body_json(json!({
                "error": "Internal Server Error"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.new_issuance(wallet_attestation).await;
        assert!(
            result.is_err(),
            "New issuance should fail with server error"
        );
        match result.unwrap_err() {
            IssuanceServiceError::ServerError { status, .. } => {
                assert_eq!(status, 500);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_server_error_check_status() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let issuance_id = "5431d6df-63da-4803-a9fc-d92e5c36b9f8".to_string();
        let wallet_attestation = "test_attestation".to_string();

        // Mock server error response
        Mock::given(method("GET"))
            .and(path(format!("/issuance/{}/status", issuance_id)))
            .respond_with(ResponseTemplate::new(404).set_body_json(json!({
                "error": "Issuance not found"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.check_status(issuance_id, wallet_attestation).await;
        assert!(
            result.is_err(),
            "Status check should fail with server error"
        );
        match result.unwrap_err() {
            IssuanceServiceError::ServerError { status, .. } => {
                assert_eq!(status, 404);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_invalid_json_response() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let wallet_attestation = "test_attestation".to_string();

        // Mock invalid JSON response
        Mock::given(method("GET"))
            .and(path("/issuance/new"))
            .respond_with(ResponseTemplate::new(200).set_body_string("invalid json"))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.new_issuance(wallet_attestation).await;
        assert!(
            result.is_err(),
            "New issuance should fail with invalid JSON"
        );
        match result.unwrap_err() {
            IssuanceServiceError::ResponseError(_) => (),
            _ => panic!("Expected ResponseError"),
        }
    }
}
