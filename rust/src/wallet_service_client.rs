use base64::engine::{general_purpose::STANDARD, Engine};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::{Arc, Mutex};
use thiserror::Error;
use time::OffsetDateTime;

#[derive(Error, Debug, uniffi::Error)]
pub enum WalletServiceError {
    /// Failed to parse the JWK as valid JSON
    #[error("Failed to parse JWK as JSON: {0}")]
    InvalidJson(String),

    /// Failed to send the login request
    #[error("Failed to send login request: {0}")]
    NetworkError(String),

    /// Server returned an error response
    #[error("Server error: {status} - {error_message}")]
    ServerError { status: u16, error_message: String },

    /// Failed to read the response body
    #[error("Failed to read response body: {0}")]
    ResponseError(String),

    /// Token is expired or invalid
    #[error("Token is expired or invalid")]
    InvalidToken,

    /// Failed to parse JWT claims
    #[error("Failed to parse JWT claims: {0}")]
    JwtParseError(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct JwtClaims {
    iss: String, // issuer
    sub: String, // subject (client_id)
    exp: f64,    // expiration time
    iat: f64,    // issued at
}

#[derive(Debug, Clone)]
struct TokenInfo {
    token: String,
    claims: JwtClaims,
    expires_at: OffsetDateTime,
}

/// Internal function to parse and validate JWT claims
fn parse_jwt_claims(token: &str) -> Result<JwtClaims, WalletServiceError> {
    // Split the JWT into parts
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() != 3 {
        return Err(WalletServiceError::JwtParseError(
            "Invalid JWT format".to_string(),
        ));
    }

    // Decode the payload (second part)
    let payload = parts[1];

    // Add padding if needed
    let padded_payload = if payload.len() % 4 != 0 {
        format!("{}{}", payload, "=".repeat(4 - (payload.len() % 4)))
    } else {
        payload.to_string()
    };

    let decoded = STANDARD.decode(padded_payload).map_err(|e| {
        WalletServiceError::JwtParseError(format!("Failed to decode JWT payload: {}", e))
    })?;

    let claims: JwtClaims = serde_json::from_slice(&decoded).map_err(|e| {
        WalletServiceError::JwtParseError(format!("Failed to parse JWT claims: {}", e))
    })?;

    Ok(claims)
}

/// Internal function to create TokenInfo from JWT
fn create_token_info(token: String) -> Result<TokenInfo, WalletServiceError> {
    let claims = parse_jwt_claims(&token)?;
    let expires_at = OffsetDateTime::from_unix_timestamp(claims.exp as i64).map_err(|e| {
        WalletServiceError::JwtParseError(format!("Invalid expiration timestamp: {}", e))
    })?;

    Ok(TokenInfo {
        token,
        claims,
        expires_at,
    })
}

#[derive(uniffi::Object)]
pub struct WalletServiceClient {
    client: Client,
    base_url: String,
    token_info: Arc<Mutex<Option<TokenInfo>>>,
}

#[uniffi::export]
impl WalletServiceClient {
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Self {
        Self {
            client: Client::new(),
            base_url,
            token_info: Arc::new(Mutex::new(None)),
        }
    }

    /// Returns the current client ID (sub claim from JWT)
    pub fn get_client_id(&self) -> Option<String> {
        if let Ok(guard) = self.token_info.lock() {
            guard.as_ref().map(|info| info.claims.sub.clone())
        } else {
            None
        }
    }

    /// Returns true if the current token is valid and not expired
    pub fn is_token_valid(&self) -> bool {
        if let Ok(guard) = self.token_info.lock() {
            if let Some(token_info) = guard.as_ref() {
                token_info.expires_at > OffsetDateTime::now_utc()
            } else {
                false
            }
        } else {
            false
        }
    }

    pub async fn login(&self, jwk: &str) -> Result<String, WalletServiceError> {
        // Parse the JWK string into a Value to ensure it's valid JSON
        let jwk_value: Value = serde_json::from_str(jwk)
            .map_err(|e| WalletServiceError::InvalidJson(e.to_string()))?;

        // Make POST request to /login endpoint
        let response = self
            .client
            .post(format!("{}/login", self.base_url))
            .header("Content-Type", "application/json")
            .json(&jwk_value)
            .send()
            .await
            .map_err(|e| WalletServiceError::NetworkError(e.to_string()))?;

        // Check if the response was successful
        if !response.status().is_success() {
            let status = response.status().as_u16();
            let error_text = response.text().await.unwrap_or_default();
            return Err(WalletServiceError::ServerError {
                status,
                error_message: error_text,
            });
        }

        // Get the response body as string
        let token = response
            .text()
            .await
            .map_err(|e| WalletServiceError::ResponseError(e.to_string()))?;

        // Parse and validate the JWT
        let token_info = create_token_info(token.clone())?;

        // Store the token info
        if let Ok(mut guard) = self.token_info.lock() {
            *guard = Some(token_info);
        }

        Ok(token)
    }

    /// Helper method to get an authorization header with the current token
    pub fn get_auth_header(&self) -> Result<String, WalletServiceError> {
        if let Ok(guard) = self.token_info.lock() {
            if let Some(token_info) = guard.as_ref() {
                if token_info.expires_at > OffsetDateTime::now_utc() {
                    Ok(format!("Bearer {}", token_info.token))
                } else {
                    Err(WalletServiceError::InvalidToken)
                }
            } else {
                Err(WalletServiceError::InvalidToken)
            }
        } else {
            Err(WalletServiceError::InvalidToken)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use time::OffsetDateTime;
    use tokio;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    async fn setup_mock_server() -> (MockServer, String) {
        let mock_server = MockServer::start().await;
        let base_url = mock_server.uri();
        (mock_server, base_url)
    }

    fn generate_valid_jwt() -> String {
        let now = OffsetDateTime::now_utc();
        let exp = now + time::Duration::hours(1);

        let claims = serde_json::json!({
            "iss": "wallet_service",
            "sub": "test_client_id",
            "exp": exp.unix_timestamp() as f64,
            "iat": now.unix_timestamp() as f64,
            "nbf": now.unix_timestamp() as f64,
            "cnf": {
                "key_ops": ["verify"],
                "alg": "ES256",
                "kid": "test_kid",
                "kty": "EC",
                "crv": "P-256",
                "x": "-hKdnYnv9nHSqtmsjCoOPomS2pmhvP19rkbncRKyuro",
                "y": "oj1ucwGXBS5UVR1i4OOXdIuJKlPnqSp391oXNZjx4Ko"
            }
        });

        // Create a JWT with the claims (header + payload + signature)
        format!("eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.{}.SSMqn__aU1z73WlUKTM7rpqvjwttXUzWswL40hPNHcT1X0ENltmVMGO2bl7YIguOOxEio7jbELQZlPuab7jFJQ",
            base64::engine::general_purpose::STANDARD.encode(claims.to_string()))
    }

    #[tokio::test]
    async fn test_successful_login() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let jwk = ssi::JWK::generate_p256().to_public().to_string();

        // Mock successful login response
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "token": generate_valid_jwt()
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(&jwk).await;
        assert!(result.is_ok(), "Login should succeed with valid JWK");

        // Verify token info was stored
        assert!(client.is_token_valid(), "Token should be valid after login");
        assert!(
            client.get_client_id().is_some(),
            "Client ID should be available after login"
        );
    }

    #[tokio::test]
    async fn test_invalid_json() {
        let (_, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let invalid_json = r#"{
            "crv": "P-256",
            "kty": "EC",
            "x": "-hKdnYnv9nHSqtmsjCoOPomS2pmhvP19rkbncRKyuro",
            "y": "oj1ucwGXBS5UVR1i4OOXdIuJKlPnqSp391oXNZjx4Ko"
        "#; // Missing closing brace

        let result = client.login(invalid_json).await;
        assert!(result.is_err(), "Login should fail with invalid JSON");
        match result.unwrap_err() {
            WalletServiceError::InvalidJson(_) => (),
            _ => panic!("Expected InvalidJson error"),
        }
    }

    #[tokio::test]
    async fn test_server_error() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let jwk = ssi::JWK::generate_p256().to_public().to_string();

        // Mock server error response
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(500).set_body_json(serde_json::json!({
                "error": "Internal Server Error"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(&jwk).await;
        assert!(result.is_err(), "Login should fail with server error");
        match result.unwrap_err() {
            WalletServiceError::ServerError { status, .. } => {
                assert_eq!(status, 500);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_empty_jwk() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let empty_jwk = "{}";

        // Mock server error response for empty JWK
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(400).set_body_json(serde_json::json!({
                "error": "Invalid JWK"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(empty_jwk).await;
        assert!(result.is_err(), "Login should fail with empty JWK");
        match result.unwrap_err() {
            WalletServiceError::ServerError { status, .. } => {
                assert_eq!(status, 400);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_malformed_jwk() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let malformed_jwk = r#"{
            "crv": "P-256",
            "kty": "EC",
            "x": "invalid-base64",
            "y": "invalid-base64"
        }"#;

        // Mock server error response for malformed JWK
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(400).set_body_json(serde_json::json!({
                "error": "Malformed JWK"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(malformed_jwk).await;
        assert!(result.is_err(), "Login should fail with malformed JWK");
        match result.unwrap_err() {
            WalletServiceError::ServerError { status, .. } => {
                assert_eq!(status, 400);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_auth_header() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let jwk = ssi::JWK::generate_p256().to_public().to_string();

        // Mock successful login response
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "token": generate_valid_jwt()
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        // Initially, auth header should fail
        assert!(
            client.get_auth_header().is_err(),
            "Auth header should fail before login"
        );

        // After successful login
        let result = client.login(&jwk).await;
        assert!(result.is_ok(), "Login should succeed");

        // Auth header should now be available
        let auth_header = client
            .get_auth_header()
            .expect("Auth header should be available after login");
        assert!(
            auth_header.starts_with("Bearer "),
            "Auth header should start with 'Bearer '"
        );
    }
}
