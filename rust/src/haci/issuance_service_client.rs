use crate::haci::http_client::HaciHttpClient;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::sync::OnceCell;

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

    /// Missing endpoint
    #[error("Endpoint key does not exists: {0}. Available keys: {1}")]
    MissingEndpoint(String, String),
}

#[derive(Debug, Serialize, Deserialize)]
struct NewIssuanceResponse {
    id: String,
}

#[derive(Debug, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "state")]
pub enum CheckStatusResponse {
    ProofingRequired { proofing_url: String },
    ReadyToProvision { openid_credential_offer: String },
}

#[derive(uniffi::Object)]
pub struct IssuanceServiceClient {
    client: HaciHttpClient,
    base_url: String,
    endpoints: OnceCell<IssuanceEndpoints>,
}

#[derive(Debug, Deserialize, Clone, uniffi::Object)]
pub struct IssuanceEndpoints {
    initiate_issuance: String,
    get_issuance_status: String,
}

impl IssuanceEndpoints {
    /// Loads the available endpoints dynamically from the API - I would like to not expose it to
    /// the app, but I'm not sure how to do it.
    async fn fetch_wellknown_from_api(
        client: &HaciHttpClient,
        base_url: &String,
    ) -> Result<Self, IssuanceServiceError> {
        let url = format!("{}/.well-known/showcase-endpoints", base_url);
        let response = client.get(url).send().await.map_err(|e| {
            IssuanceServiceError::NetworkError(format!(
                "Issuance endpoints fetching error: {:?}",
                e.to_string()
            ))
        })?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let error_text = response.text().await.unwrap_or_default();
            return Err(IssuanceServiceError::ServerError {
                status,
                error_message: format!("Issuance endpoints fetching error: {:?}", error_text),
            });
        }

        let endpoints: Self = response.json().await.map_err(|e| {
            IssuanceServiceError::ResponseError(format!(
                "Issuance endpoints fetching error: {:?}",
                e.to_string()
            ))
        })?;

        Ok(endpoints)
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl IssuanceServiceClient {
    /// Creates a new IssuanceServiceClient instance
    ///
    /// # Arguments
    /// * `base_url` - The base URL of the issuance service
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Self {
        let actual_url = base_url
            .trim()
            .strip_suffix('/')
            .unwrap_or(&base_url)
            .to_string();
        Self {
            client: HaciHttpClient::new(),
            base_url: actual_url,
            endpoints: OnceCell::new(),
        }
    }

    /// Lazy fetch or return cached endpoints
    async fn get_or_fetch_endpoints(&self) -> Result<IssuanceEndpoints, IssuanceServiceError> {
        self.endpoints
            .get_or_try_init(async || {
                IssuanceEndpoints::fetch_wellknown_from_api(&self.client, &self.base_url).await
            })
            .await
            .cloned()
    }

    /// Format the endpoint
    fn format_endpoint(&self, path: String) -> String {
        format!("{}{}", self.base_url, path)
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
        let path = &self
            .get_or_fetch_endpoints()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?
            .initiate_issuance;

        let url = self.format_endpoint(path.clone());

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
        // This endpoint is expected to have an {issuance_id} space
        // get_issuance_status: "/issuance/{issuance_id}/status"
        let path = &self
            .get_or_fetch_endpoints()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?
            .get_issuance_status;

        let url = self.format_endpoint(path.clone());

        if !url.contains("{issuance_id}") {
            return Err(IssuanceServiceError::InternalError(
                "URL template missing {issuance_id} placeholder".to_string(),
            ));
        }
        let complete_url = url.replace("{issuance_id}", issuance_id.as_str());

        if complete_url.contains("{issuance_id}") {
            return Err(IssuanceServiceError::InternalError(
                "Failed to replace {issuance_id} placeholder".to_string(),
            ));
        }

        let response = self
            .client
            .get(complete_url)
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
    async fn test_successful_new_issuance() -> Result<(), IssuanceServiceError> {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let wallet_attestation = "test_attestation".to_string();
        let expected_id = "d94062ab-e659-4b70-8532-b758973c2b40".to_string();

        // Mock lazy call to discover available endpoints
        Mock::given(method("GET"))
            .and(path("/.well-known/showcase-endpoints"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{
                    "base_url": "http://localhost:3002",
                    "initiate_issuance": "/issuance/new",
                    "get_issuance_status": "/issuance/{issuance_id}/status"
                }"#,
            ))
            .expect(1)
            .mount(&mock_server)
            .await;

        let endpoint = &client
            .get_or_fetch_endpoints()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?
            .initiate_issuance;

        // Mock successful new issuance response
        Mock::given(method("GET"))
            .and(path(endpoint))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "id": expected_id
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.new_issuance(wallet_attestation).await;
        assert!(result.is_ok(), "New issuance should succeed");
        assert_eq!(result.unwrap(), expected_id);

        Ok(())
    }

    #[tokio::test]
    async fn test_successful_check_status() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let issuance_id = "5431d6df-63da-4803-a9fc-d92e5c36b9f8".to_string();
        let wallet_attestation = "test_attestation".to_string();

        // Mock lazy call to discover available endpoints
        Mock::given(method("GET"))
            .and(path("/.well-known/showcase-endpoints"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{
                    "base_url": "http://localhost:3002",
                    "initiate_issuance": "/issuance/new",
                    "get_issuance_status": "/issuance/{issuance_id}/status"
                }"#,
            ))
            .expect(1)
            .mount(&mock_server)
            .await;

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

        match response {
            CheckStatusResponse::ReadyToProvision {
                openid_credential_offer,
            } => {
                assert_eq!(openid_credential_offer, "openid_credential_offer");
            }
            CheckStatusResponse::ProofingRequired { proofing_url: _ } => {
                panic!("Expected ReadyToProvision state")
            }
        }
    }

    #[tokio::test]
    async fn test_successful_check_status_proofing_required() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let issuance_id = "5431d6df-63da-4803-a9fc-d92e5c36b9f8".to_string();
        let wallet_attestation = "test_attestation".to_string();

        // Mock lazy call to discover available endpoints
        Mock::given(method("GET"))
            .and(path("/.well-known/showcase-endpoints"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{
                    "base_url": "http://localhost:3002",
                    "initiate_issuance": "/issuance/new",
                    "get_issuance_status": "/issuance/{issuance_id}/status"
                }"#,
            ))
            .expect(1)
            .mount(&mock_server)
            .await;

        // Mock successful status check response
        Mock::given(method("GET"))
            .and(path(format!("/issuance/{}/status", issuance_id)))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "state": "ProofingRequired",
                "proofing_url": "proofing_url"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.check_status(issuance_id, wallet_attestation).await;
        println!("Result {:?}", result);
        assert!(result.is_ok(), "Status check should succeed");
        let response = result.unwrap();

        match response {
            CheckStatusResponse::ReadyToProvision {
                openid_credential_offer: _,
            } => {
                panic!("Expected ProofingRequired state")
            }
            CheckStatusResponse::ProofingRequired { proofing_url } => {
                assert_eq!(proofing_url, "proofing_url");
            }
        }
    }

    #[tokio::test]
    async fn test_server_error_new_issuance() -> Result<(), IssuanceServiceError> {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let wallet_attestation = "test_attestation".to_string();

        // Mock lazy call to discover available endpoints
        Mock::given(method("GET"))
            .and(path("/.well-known/showcase-endpoints"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{
                    "base_url": "http://localhost:3002",
                    "initiate_issuance": "/issuance/new",
                    "get_issuance_status": "/issuance/{issuance_id}/status"
                }"#,
            ))
            .expect(1)
            .mount(&mock_server)
            .await;

        let endpoint = &client
            .get_or_fetch_endpoints()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?
            .initiate_issuance;

        // Mock server error response
        Mock::given(method("GET"))
            .and(path(endpoint))
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

        Ok(())
    }

    #[tokio::test]
    async fn test_server_error_check_status() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let issuance_id = "5431d6df-63da-4803-a9fc-d92e5c36b9f8".to_string();
        let wallet_attestation = "test_attestation".to_string();

        // Mock lazy call to discover available endpoints
        Mock::given(method("GET"))
            .and(path("/.well-known/showcase-endpoints"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{
                    "base_url": "http://localhost:3002",
                    "initiate_issuance": "/issuance/new",
                    "get_issuance_status": "/issuance/{issuance_id}/status"
                }"#,
            ))
            .expect(1)
            .mount(&mock_server)
            .await;

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
    async fn test_invalid_json_response() -> Result<(), IssuanceServiceError> {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = IssuanceServiceClient::new(base_url);
        let wallet_attestation = "test_attestation".to_string();

        // Mock lazy call to discover available endpoints
        Mock::given(method("GET"))
            .and(path("/.well-known/showcase-endpoints"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{
                    "base_url": "http://localhost:3002",
                    "initiate_issuance": "/issuance/new",
                    "get_issuance_status": "/issuance/{issuance_id}/status"
                }"#,
            ))
            .expect(1)
            .mount(&mock_server)
            .await;

        let endpoint = &client
            .get_or_fetch_endpoints()
            .await
            .map_err(|e| IssuanceServiceError::ResponseError(e.to_string()))?
            .initiate_issuance;

        // Mock invalid JSON response
        Mock::given(method("GET"))
            .and(path(endpoint))
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

        Ok(())
    }
}
