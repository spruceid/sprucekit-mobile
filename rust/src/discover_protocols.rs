/// FFI surface for `interaction:` protocol discovery (§3.7.4).
use std::collections::HashMap;

/// Errors from protocol discovery.
#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum DiscoveryError {
    /// A transport-level failure (stringified `reqwest::Error`).
    #[error("Network error: {0}")]
    Network(String),

    /// Interaction URL provided is invalid
    #[error("invalid URL: {0}")]
    InvalidUrl(String),

    /// A 5xx (or otherwise non-2xx/4xx) server response.
    #[error("Server returned error status {status}")]
    ServerError { status: u16, body: String },

    /// A response body failed to deserialize (stringified `serde_json::Error`).
    #[error("Failed to deserialize response: {0}")]
    Deserialization(String),

    /// A response body exceeded the configured size cap (B.4).
    #[error("response body exceeded the {limit_bytes}-byte limit")]
    ResponseTooLarge { limit_bytes: u64 },

    /// A non-HTTPS (or non-HTTP-scheme) URL was rejected (§3.7.1 / B.2). Plain
    /// `http` is only accepted for loopback hosts (local development).
    #[error("insecure URL rejected: {0}")]
    InsecureUrl(String),
}

impl From<vcalm_rs::discover_protocols::DiscoveryError> for DiscoveryError {
    fn from(e: vcalm_rs::discover_protocols::DiscoveryError) -> Self {
        use vcalm_rs::discover_protocols::DiscoveryError as E;
        match e {
            E::Network(v) => Self::Network(v),
            E::InvalidUrl(v) => Self::InvalidUrl(v),
            E::ServerError { status, body } => Self::ServerError { status, body },
            E::Deserialization(v) => Self::Deserialization(v),
            E::ResponseTooLarge { limit_bytes } => Self::ResponseTooLarge { limit_bytes },
            E::InsecureUrl(v) => Self::InsecureUrl(v),
        }
    }
}

/// Resolve every protocol exchange URL advertised by an `interaction:` discovery
/// endpoint.
///
/// The URL must be HTTPS, or loopback `http` for local development (§3.7.1/B.2);
/// other schemes are rejected. Response bodies are size-capped (B.4).
#[uniffi::export(async_runtime = "tokio")]
pub async fn discover_protocols(
    interaction_url: &str,
) -> Result<HashMap<String, String>, DiscoveryError> {
    vcalm_rs::discover_protocols::discover_protocols(interaction_url)
        .await
        .map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn delegates_and_returns_the_protocol_map() {
        let server = MockServer::start().await;
        let base = server.uri();
        let vcapi_url = format!("{base}/workflows/1/exchanges/2");

        Mock::given(method("GET"))
            .and(path("/interactions/abc"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "protocols": { "vcapi": vcapi_url }
            })))
            .expect(1)
            .mount(&server)
            .await;

        let protocols = discover_protocols(&format!("{base}/interactions/abc"))
            .await
            .expect("discovery succeeds through the wrapper");
        assert_eq!(
            protocols.get("vcapi").map(String::as_str),
            Some(vcapi_url.as_str())
        );
    }

    #[tokio::test]
    async fn maps_the_upstream_error_onto_the_ffi_error() {
        // Rejected before any request, so this also proves the scheme allowlist
        // is still in force through the delegation.
        let err = discover_protocols("file:///etc/passwd")
            .await
            .expect_err("file: scheme must be rejected");
        assert!(matches!(err, DiscoveryError::InsecureUrl(_)));
    }
}
