use reqwest::header::ACCEPT;
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

    // Interaction URL provided is invalid
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
/// Resolve all protocol exchange URLs from an `interaction:` discovery endpoint.
/// The discovery URL must pass [`validate_endpoint_url`] (HTTPS, or loopback http
/// for local dev — §3.7.1/B.2; also rejects `file:`/other schemes a QR code could smuggle in).
pub async fn discover_protocols(
    discovery_url: &str,
) -> Result<HashMap<String, String>, DiscoveryError> {
    let discovery_url = Url::parse(discovery_url)
        .map_err(|e| DiscoveryError::InvalidUrl(format!("invalid exchange URL: {e}")))?;
    validate_endpoint_url(&discovery_url)?;

    let client = reqwest::Client::builder()
        .use_rustls_tls()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| DiscoveryError::Network(e.to_string()))?;

    let resp = client
        .get(discovery_url)
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
