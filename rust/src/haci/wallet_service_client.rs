use crate::haci::http_client::HaciHttpClient;
use serde_json::Value;
use ssi::{
    claims::jwt::{ExpirationTime, StringOrURI, Subject, ToDecodedJwt},
    prelude::*,
};
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

    /// Internal error
    #[error("Internal error: {0}")]
    InternalError(String),
}

#[derive(Debug, Clone)]
struct TokenInfo {
    token: String,
    claims: JWTClaims,
    expires_at: OffsetDateTime,
}

/// Internal function to create TokenInfo from JWT
fn create_token_info(token: String) -> Result<TokenInfo, WalletServiceError> {
    let jws_bytes: Vec<u8> = token.as_bytes().to_vec();

    let jws_buf = JwsBuf::new(jws_bytes)
        .map_err(|e| WalletServiceError::JwtParseError(format!("Failed to parse JWS: {:?}", e)))?;

    let jwt_claims = jws_buf
        .to_decoded_jwt()
        .map_err(|e| WalletServiceError::JwtParseError(format!("Failed to decode JWT: {:?}", e)))?
        .signing_bytes
        .payload;

    // Get expiration time from claims
    let exp = jwt_claims
        .registered
        .get::<ExpirationTime>()
        .ok_or_else(|| WalletServiceError::JwtParseError("Missing expiration time".to_string()))?;

    let expires_at =
        OffsetDateTime::from_unix_timestamp(exp.0.as_seconds() as i64).map_err(|e| {
            WalletServiceError::JwtParseError(format!("Invalid expiration timestamp: {}", e))
        })?;

    Ok(TokenInfo {
        token,
        claims: jwt_claims,
        expires_at,
    })
}

#[derive(uniffi::Object)]
pub struct WalletServiceClient {
    client: HaciHttpClient,
    base_url: String,
    token_info: Arc<Mutex<Option<TokenInfo>>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl WalletServiceClient {
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Self {
        Self {
            client: HaciHttpClient::new(),
            base_url,
            token_info: Arc::new(Mutex::new(None)),
        }
    }

    /// Returns the current client ID (sub claim from JWT)
    pub fn get_client_id(&self) -> Option<String> {
        if let Ok(guard) = self.token_info.lock() {
            guard.as_ref().and_then(|info| {
                info.claims
                    .registered
                    .get::<Subject>()
                    .map(|sub| match &sub.0 {
                        StringOrURI::String(s) => s.to_string(),
                        StringOrURI::URI(u) => u.to_string(),
                    })
            })
        } else {
            None
        }
    }

    /// Get the current token
    pub fn get_token(&self) -> Option<String> {
        if let Ok(guard) = self.token_info.lock() {
            guard.as_ref().map(|token_info| token_info.token.clone())
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

    /// Get a nonce from the server that expires in 5 minutes and can only be used once
    pub async fn nonce(&self) -> Result<String, WalletServiceError> {
        // Make GET request to /nonce endpoint
        let response = self
            .client
            .get(format!("{}/nonce", self.base_url))
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
        let nonce = response
            .text()
            .await
            .map_err(|e| WalletServiceError::ResponseError(e.to_string()))?;

        Ok(nonce)
    }

    pub async fn login(&self, app_attestation: &str) -> Result<String, WalletServiceError> {
        // Parse the app attestation string into a Value to ensure it's valid JSON
        let attestation_value: Value = serde_json::from_str(app_attestation)
            .map_err(|e| WalletServiceError::InvalidJson(e.to_string()))?;

        // Make POST request to /login endpoint
        let response = self
            .client
            .post(format!("{}/login", self.base_url))
            .header("Content-Type", "application/json")
            .json(&attestation_value)
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

        // Store the token info
        let token_info = create_token_info(token.clone())?;

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
    use serde_json::to_value;
    use ssi::claims::jwt::{AnyClaims, IssuedAt, Issuer, NotBefore, NumericDate};
    use time::OffsetDateTime;
    use tokio;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    const MOCK_APP_ATTESTATION: &str =
        include_str!("../../../rust/tests/res/ios-app-attestation.json");

    async fn setup_mock_server() -> (MockServer, String) {
        let mock_server = MockServer::start().await;
        let base_url = mock_server.uri();
        (mock_server, base_url)
    }

    async fn generate_valid_jwt(jwk: JWK) -> String {
        let now = OffsetDateTime::now_utc();
        let exp = now + time::Duration::hours(1);

        let mut claims: JWTClaims<AnyClaims> = JWTClaims::default();
        claims.registered.set(ExpirationTime(NumericDate::from(
            exp.unix_timestamp() as i32
        )));
        claims
            .registered
            .set(IssuedAt(NumericDate::from(now.unix_timestamp() as i32)));
        claims
            .registered
            .set(NotBefore(NumericDate::from(now.unix_timestamp() as i32)));
        claims
            .registered
            .set(Issuer(StringOrURI::String("wallet_service".to_string())));
        claims
            .registered
            .set(Subject(StringOrURI::String("test_client_id".to_string())));

        let public_jwk = jwk.to_public();
        let cnf = to_value(public_jwk).unwrap();
        claims.private.set("cnf".to_string(), cnf);

        let jws = claims.sign(jwk).await.unwrap();

        jws.to_string()
    }

    #[tokio::test]
    async fn test_get_nonce() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let expected_nonce = "test-nonce-123";

        // Mock successful nonce response
        Mock::given(method("GET"))
            .and(path("/nonce"))
            .respond_with(ResponseTemplate::new(200).set_body_string(expected_nonce))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.nonce().await;
        assert!(result.is_ok(), "Nonce request should succeed");
        assert_eq!(result.unwrap(), expected_nonce);
    }

    #[tokio::test]
    async fn test_nonce_server_error() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);

        // Mock server error response
        Mock::given(method("GET"))
            .and(path("/nonce"))
            .respond_with(ResponseTemplate::new(500).set_body_json(serde_json::json!({
                "error": "Internal Server Error"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.nonce().await;
        assert!(
            result.is_err(),
            "Nonce request should fail with server error"
        );
        match result.unwrap_err() {
            WalletServiceError::ServerError { status, .. } => {
                assert_eq!(status, 500);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_successful_login() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);

        // Generate a new private key for signing
        let private_jwk = JWK::generate_p256();

        // Mock successful login response
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_bytes(generate_valid_jwt(private_jwk).await.as_bytes()),
            )
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(MOCK_APP_ATTESTATION).await;
        assert!(
            result.is_ok(),
            "Login should succeed with valid app attestation"
        );

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
            "keyAssertion": "invalid",
            "clientData": "invalid",
            "keyAttestation": "invalid"
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

        // Mock server error response
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(500).set_body_json(serde_json::json!({
                "error": "Internal Server Error"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(MOCK_APP_ATTESTATION).await;
        assert!(result.is_err(), "Login should fail with server error");
        match result.unwrap_err() {
            WalletServiceError::ServerError { status, .. } => {
                assert_eq!(status, 500);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_empty_attestation() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let empty_attestation = "{}";

        // Mock server error response for empty attestation
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(400).set_body_json(serde_json::json!({
                "error": "Invalid Attestation"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(empty_attestation).await;
        assert!(result.is_err(), "Login should fail with empty attestation");
        match result.unwrap_err() {
            WalletServiceError::ServerError { status, .. } => {
                assert_eq!(status, 400);
            }
            _ => panic!("Expected ServerError"),
        }
    }

    #[tokio::test]
    async fn test_malformed_attestation() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);
        let malformed_attestation = r#"{
            "keyAssertion": "invalid-base64",
            "clientData": "invalid-base64",
            "keyAttestation": "invalid-base64"
        }"#;

        // Mock server error response for malformed attestation
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(400).set_body_json(serde_json::json!({
                "error": "Malformed Attestation"
            })))
            .expect(1)
            .mount(&mock_server)
            .await;

        let result = client.login(malformed_attestation).await;
        assert!(
            result.is_err(),
            "Login should fail with malformed attestation"
        );
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

        // Generate a new private key for signing
        let private_jwk = JWK::generate_p256();

        // Mock successful login response
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_bytes(generate_valid_jwt(private_jwk).await.as_bytes()),
            )
            .expect(1)
            .mount(&mock_server)
            .await;

        // Initially, auth header should fail
        assert!(
            client.get_auth_header().is_err(),
            "Auth header should fail before login"
        );

        // After successful login
        let result = client.login(MOCK_APP_ATTESTATION).await;
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
