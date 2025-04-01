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
    println!("token: {:?}", token);
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
    async fn test_successful_login() {
        let (mock_server, base_url) = setup_mock_server().await;
        let client = WalletServiceClient::new(base_url);

        // Generate a new private key for signing
        let private_jwk = JWK::generate_p256();
        let public_jwk = private_jwk.to_public();
        let jwk_string = public_jwk.to_string();

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

        let result = client.login(&jwk_string).await;
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

        // Generate a new private key for signing
        let private_jwk = JWK::generate_p256();
        let public_jwk = private_jwk.to_public();
        let jwk_string = public_jwk.to_string();

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
        let result = client.login(&jwk_string).await;
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
