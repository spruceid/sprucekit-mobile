use reqwest::header::ACCEPT;
use reqwest::Client;
use std::collections::HashMap;
use std::time::Duration;
use url::Url;

/// The discovery document returned for an `interaction:` initiation (§3.7.4).
#[derive(serde::Deserialize)]
struct DiscoveryResponse {
    protocols: HashMap<String, String>,
}
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

/// Cap on a discovery/exchange response body (B.4: large payloads can trigger
/// DoS incidents — a malicious or broken server must not be able to exhaust the
/// wallet's memory). Generous for any plausible VPR/VP payload.
pub(crate) const MAX_RESPONSE_BYTES: usize = 10 * 1024 * 1024;

#[uniffi::export(async_runtime = "tokio")]
/// Standalone FFI entry point: no caller-supplied client, so build a fresh one
pub async fn discover_protocols(
    interaction_url: &str,
) -> Result<HashMap<String, String>, DiscoveryError> {
    let new_client = build_http_client()?;
    discover_protocols_with_client(interaction_url, &new_client).await
}

/// Resolve all protocol exchange URLs from an `interaction:` discovery endpoint.
/// The discovery URL must pass [`validate_endpoint_url`] (HTTPS, or loopback http
/// for local dev — §3.7.1/B.2; also rejects `file:`/other schemes a QR code could smuggle in).
pub(crate) async fn discover_protocols_with_client(
    interaction_url: &str,
    client: &Client,
) -> Result<HashMap<String, String>, DiscoveryError> {
    let interaction_url = Url::parse(interaction_url)
        .map_err(|e| DiscoveryError::InvalidUrl(format!("invalid interaction URL: {e}")))?;
    validate_endpoint_url(&interaction_url)?;

    if let Some((_, version)) = interaction_url.query_pairs().find(|(k, _)| k == "iuv") {
        if version != "1" {
            return Err(DiscoveryError::InvalidUrl(format!(
                "unsupported interaction URL version: iuv={version} (expected 1)"
            )));
        }
    }

    let resp = client
        .get(interaction_url)
        .header(ACCEPT, "application/json")
        .send()
        .await
        .map_err(|e| DiscoveryError::Network(e.to_string()))?;

    let status = resp.status();
    let body = read_body_capped(resp).await?;

    if !status.is_success() {
        return Err(DiscoveryError::ServerError {
            status: status.as_u16(),
            body,
        });
    }

    let discovery: DiscoveryResponse =
        serde_json::from_str(&body).map_err(|e| DiscoveryError::Deserialization(e.to_string()))?;

    Ok(discovery.protocols)
}

/// §3.7.1: The interaction URL must be an HTTPS URL that contains an interaction-specific identifier.
/// The URL SHOULD be opaque and require no URL syntax processing before it is fetched by the receiving
/// system — the HTTPS origin is the trust signal the whole interaction model hangs on, and a bearer
/// token must never travel over plaintext. Plain `http` is allowed ONLY for loopback hosts (local
/// development/test servers); every other scheme (`file:`, custom schemes a QR code could smuggle in)
/// is rejected.
pub(crate) fn validate_endpoint_url(url: &Url) -> Result<(), DiscoveryError> {
    match url.scheme() {
        "https" => Ok(()),
        "http" => {
            let loopback = match url.host() {
                Some(url::Host::Ipv4(ip)) => ip.is_loopback(),
                Some(url::Host::Ipv6(ip)) => ip.is_loopback(),
                Some(url::Host::Domain(d)) => d.eq_ignore_ascii_case("localhost"),
                None => false,
            };
            if loopback {
                Ok(())
            } else {
                Err(DiscoveryError::InsecureUrl(format!(
                    "plain http is only allowed for loopback hosts, got {url}"
                )))
            }
        }
        other => Err(DiscoveryError::InsecureUrl(format!(
            "unsupported URL scheme `{other}`"
        ))),
    }
}

/// Read a response body with a hard size cap (B.4). Checks `Content-Length`
/// first, then enforces the cap while streaming, so a server that lies about
/// (or omits) the length still cannot exhaust memory.
pub(crate) async fn read_body_capped(
    mut resp: reqwest::Response,
) -> Result<String, DiscoveryError> {
    if let Some(len) = resp.content_length() {
        if len > MAX_RESPONSE_BYTES as u64 {
            return Err(DiscoveryError::ResponseTooLarge {
                limit_bytes: MAX_RESPONSE_BYTES as u64,
            });
        }
    }
    let mut buf: Vec<u8> = Vec::new();
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| DiscoveryError::Network(e.to_string()))?
    {
        if buf.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err(DiscoveryError::ResponseTooLarge {
                limit_bytes: MAX_RESPONSE_BYTES as u64,
            });
        }
        buf.extend_from_slice(&chunk);
    }
    String::from_utf8(buf)
        .map_err(|e| DiscoveryError::Deserialization(format!("response body is not UTF-8: {e}")))
}

pub(crate) fn build_http_client() -> Result<Client, DiscoveryError> {
    let client = reqwest::Client::builder()
        .use_rustls_tls()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| DiscoveryError::Network(e.to_string()))?;

    Ok(client)
}

#[cfg(test)]
mod tests {
    use crate::discover_protocols::{discover_protocols, DiscoveryError};
    use serde_json::json;
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn initiation_interaction_runs_discovery() {
        // Full protocol map returned from server when valid interaction URL is fetched using `Accept: application/json` header (§3.7.4)
        let server = MockServer::start().await;
        let base = server.uri();

        // Representative URLs returned for each protocol
        let vcapi_url = format!("{base}/workflows/123/exchanges/987");
        let oid4vp_url = "openid4vp://?client_id=https%3A%2F%2Fapp.example%2Fworkflows%2F123%2Fexchanges%2F987%2Fopenid%2Fclient%2Fauthorization%2Fresponse&request_uri=https%3A%2F%2Fapp.example%2Fworkflows%2F123%2Fexchanges%2F987%2Fopenid%2Fclient%2Fauthorization%2Frequest";

        Mock::given(method("GET"))
            .and(path("/interactions/z8n38Dp7a"))
            .and(header("accept", "application/json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "protocols": {
                    "vcapi": vcapi_url,
                    "OID4VP": oid4vp_url,
                }
            })))
            .expect(1)
            .mount(&server)
            .await;

        let result = discover_protocols(&format!("{base}/interactions/z8n38Dp7a?iuv=1"))
            .await
            .expect("all supported protocols should be returned");

        assert_eq!(result.len(), 2);
        assert_eq!(
            result.get("vcapi").map(String::as_str),
            Some(vcapi_url.as_str())
        );
        assert_eq!(result.get("OID4VP").map(String::as_str), Some(oid4vp_url));
    }

    #[tokio::test]
    async fn server_5xx_is_server_error() {
        // 500 server response results in `DiscoveryError::ServerError`
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("GET"))
            .and(path("/interactions/z8n38Dp7a"))
            .respond_with(ResponseTemplate::new(500).set_body_string("boom"))
            .mount(&server)
            .await;

        let err = discover_protocols(&format!("{base}/interactions/z8n38Dp7a"))
            .await
            .expect_err("a 500 response must be an error");

        match err {
            DiscoveryError::ServerError { status, body } => {
                assert_eq!(status, 500);
                assert_eq!(body, "boom");
            }
            other => panic!("expected ServerError, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn server_4xx_is_server_error() {
        // 4xx responses are also surfaced as `DiscoveryError::ServerError`.
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("GET"))
            .and(path("/interactions/z8n38Dp7a"))
            .respond_with(ResponseTemplate::new(404))
            .mount(&server)
            .await;

        let err = discover_protocols(&format!("{base}/interactions/z8n38Dp7a"))
            .await
            .expect_err("a 404 response must be an error");

        assert!(matches!(
            err,
            DiscoveryError::ServerError { status: 404, .. }
        ));
    }

    #[tokio::test]
    async fn non_json_body_is_deserialization_error() {
        // Non-JSON response body results in `DiscoveryError::Deserialization`
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("GET"))
            .and(path("/interactions/z8n38Dp7a"))
            .respond_with(ResponseTemplate::new(200).set_body_string("<html>not json</html>"))
            .mount(&server)
            .await;

        let err = discover_protocols(&format!("{base}/interactions/z8n38Dp7a"))
            .await
            .expect_err("a non-JSON body must fail to deserialize");

        assert!(matches!(err, DiscoveryError::Deserialization(_)));
    }

    #[tokio::test]
    async fn json_without_protocols_key_is_deserialization_error() {
        // A 200 response without the `protocols` key results in `DiscoveryError::Deserialization`
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("GET"))
            .and(path("/interactions/z8n38Dp7a"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "foo": "bar" })))
            .mount(&server)
            .await;

        let err = discover_protocols(&format!("{base}/interactions/z8n38Dp7a"))
            .await
            .expect_err("JSON missing `protocols` must be a deserialization error");

        assert!(matches!(err, DiscoveryError::Deserialization(_)));
    }

    #[tokio::test]
    async fn non_loopback_http_is_insecure() {
        // A non-loopback plain-http URL is rejected (§3.7.1/B.2).
        let err = discover_protocols("http://example.com/interactions/z8n38Dp7a")
            .await
            .expect_err("plain http to a non-loopback host must be rejected");

        assert!(matches!(err, DiscoveryError::InsecureUrl(_)));
    }

    /// A `file:` URL (the sort a malicious QR code could smuggle in) is rejected.
    #[tokio::test]
    async fn file_scheme_is_insecure() {
        let err = discover_protocols("file:///etc/passwd")
            .await
            .expect_err("file: scheme must be rejected");
        assert!(matches!(err, DiscoveryError::InsecureUrl(_)));
    }

    #[tokio::test]
    async fn unsupported_iuv_version_is_invalid_url() {
        // Unsupported interaction-URL version (`iuv` != 1) is rejected
        let err = discover_protocols("https://example.com/interactions/z8n38Dp7a?iuv=2")
            .await
            .expect_err("iuv=2 is not supported");

        assert!(matches!(err, DiscoveryError::InvalidUrl(_)));
    }

    #[tokio::test]
    async fn malformed_url_is_invalid_url() {
        // A syntactically malformed URL is rejected as `InvalidUrl`.
        let err = discover_protocols("not a url")
            .await
            .expect_err("a malformed URL must be rejected");
        assert!(matches!(err, DiscoveryError::InvalidUrl(_)));
    }
}
