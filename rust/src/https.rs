//! The https-only rule for URLs that the Rust layer contacts.

use std::{
    net::{Ipv4Addr, Ipv6Addr},
    sync::atomic::{AtomicBool, Ordering},
};

use url::{Host, Url};

/// A URL that [`require_https`] refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("refused {what} `{url}`: https is required")]
pub(crate) struct InsecureUrl {
    /// What the URL is for, for example `response_uri`.
    pub what: String,
    /// The refused URL.
    pub url: String,
}

static ALLOW_DEV_HTTP: AtomicBool = AtomicBool::new(false);

/// Allow plain http to development hosts: `localhost`, loopback and private LAN addresses
/// (IPv4 `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, IPv6 `::1` and
/// `fc00::/7`).
///
/// Off by default, in debug and release builds alike. Set it once, at startup, before any
/// request. It applies to the requests that go through the Rust layer's HTTP client (OID4VP
/// `request_uri` and `response_uri` included; OID4VCI calls its native client directly and is
/// not covered) and to the redirects that the SDK's iOS clients and the Rust fallback follow.
/// Enable it only in development builds that talk to a local verifier: plain http can be read
/// and changed by anyone on the network.
#[uniffi::export]
pub fn set_allow_dev_http(allow: bool) {
    ALLOW_DEV_HTTP.store(allow, Ordering::Relaxed);
}

/// Whether the SDK would send a request to `url`: `https`, or plain http to a development
/// host after [`set_allow_dev_http`]. A string that is not a URL is refused.
///
/// Native HTTP clients call this to check the redirects they follow on their own.
#[uniffi::export]
pub fn is_allowed_request_url(url: String) -> bool {
    require_https_str(&url, "request URL").is_ok()
}

fn allow_dev_http() -> bool {
    ALLOW_DEV_HTTP.load(Ordering::Relaxed)
}

/// Refuse a URL that is not `https`, except plain http to a development host after
/// [`set_allow_dev_http`].
///
/// Hosts are compared as parsed [`Host`] values, so a domain such as
/// `localhost.evil.example` or `dev.local` is refused. `what` names the URL in the error, for
/// example `response_uri`.
pub(crate) fn require_https(url: &Url, what: &str) -> Result<(), InsecureUrl> {
    check(url, what, allow_dev_http())
}

fn check(url: &Url, what: &str, allow_dev_http: bool) -> Result<(), InsecureUrl> {
    match url.scheme() {
        "https" => Ok(()),
        "http" if allow_dev_http && url.host().is_some_and(is_dev_host) => Ok(()),
        _ => Err(InsecureUrl {
            what: what.to_string(),
            url: url.to_string(),
        }),
    }
}

/// Parse `url` and apply [`require_https`] to it. A URL that does not parse is refused too.
pub(crate) fn require_https_str(url: &str, what: &str) -> Result<(), InsecureUrl> {
    let refused = || InsecureUrl {
        what: what.to_string(),
        url: url.to_string(),
    };
    require_https(&Url::parse(url).map_err(|_| refused())?, what)
}

/// Apply [`require_https`] to every `request_uri` query parameter of an authorization request
/// URL.
pub(crate) fn require_https_request_uri(authorization_request: &Url) -> Result<(), InsecureUrl> {
    authorization_request
        .query_pairs()
        .filter(|(name, _)| name == "request_uri")
        .try_for_each(|(_, request_uri)| require_https_str(&request_uri, "request_uri"))
}

fn is_dev_host(host: Host<&str>) -> bool {
    match host {
        Host::Domain(domain) => domain.eq_ignore_ascii_case("localhost"),
        Host::Ipv4(ip) => is_dev_ipv4(ip),
        Host::Ipv6(ip) => is_dev_ipv6(ip),
    }
}

fn is_dev_ipv4(ip: Ipv4Addr) -> bool {
    ip.is_loopback() || ip.is_private()
}

/// `::1` or the unique local range `fc00::/7`.
fn is_dev_ipv6(ip: Ipv6Addr) -> bool {
    ip.is_loopback() || (ip.segments()[0] & 0xfe00) == 0xfc00
}

/// Test helpers for the https rule.
#[cfg(test)]
pub(crate) mod test_support {
    use tokio::sync::{Mutex, MutexGuard};

    /// Serializes the tests that depend on [`super::set_allow_dev_http`], which is process-wide.
    static DEV_HTTP: Mutex<()> = Mutex::const_new(());

    /// Holds the dev-http setting at `allow` until dropped, then turns it off.
    pub(crate) struct DevHttp(#[allow(dead_code)] MutexGuard<'static, ()>);

    impl DevHttp {
        pub(crate) async fn set(allow: bool) -> Self {
            let guard = DEV_HTTP.lock().await;
            super::set_allow_dev_http(allow);
            Self(guard)
        }
    }

    impl Drop for DevHttp {
        fn drop(&mut self) {
            super::set_allow_dev_http(false);
        }
    }

    /// Assert that `message` is the error of a URL refused as `what`.
    pub(crate) fn assert_refused(message: &str, what: &str, url: &str) {
        assert!(
            message.contains(&format!("refused {what} `{url}`"))
                && message.contains("https is required"),
            "unexpected message: {message}"
        );
    }
}

#[cfg(test)]
mod tests {
    use super::{test_support::DevHttp, *};

    /// Applies the rule with the dev exception on, so a refusal comes from the scheme or host.
    fn check_url(url: &str) -> Result<(), InsecureUrl> {
        check(&Url::parse(url).unwrap(), "response_uri", true)
    }

    #[test]
    fn accepts_https() {
        assert!(check_url("https://verifier.example/cb").is_ok());
        assert!(check_url("https://127.0.0.1:8443/cb").is_ok());
        assert!(check_url("https://10.0.2.2/cb").is_ok());
    }

    #[test]
    fn refuses_http_to_a_public_host() {
        let error = check_url("http://evil.example/cb").unwrap_err();
        assert_eq!(error.what, "response_uri");
        assert_eq!(error.url, "http://evil.example/cb");
        assert_eq!(
            error.to_string(),
            "refused response_uri `http://evil.example/cb`: https is required"
        );
    }

    #[test]
    fn refuses_http_to_hosts_outside_the_dev_ranges() {
        for url in [
            "http://172.15.0.1/cb",
            "http://172.32.0.1/cb",
            "http://169.254.1.1/cb",
            "http://100.64.0.1/cb",
            "http://8.8.8.8/cb",
            "http://[fe80::1]/cb",
            "http://[2001:db8::1]/cb",
            "http://[::ffff:127.0.0.1]/cb",
            "http://localhost.evil.example/cb",
            "http://127.0.0.1.evil.example/cb",
            "http://dev.local/cb",
        ] {
            assert!(check_url(url).is_err(), "{url} should be refused");
        }
    }

    #[test]
    fn refuses_other_schemes() {
        for url in [
            "ftp://127.0.0.1/request",
            "ftp://verifier.example/request",
            "file:///etc/passwd",
            "openid4vp://?request_uri=x",
            "data:text/plain,hello",
            "ws://127.0.0.1/cb",
        ] {
            assert!(check_url(url).is_err(), "{url} should be refused");
        }
    }

    #[test]
    fn refuses_a_url_that_does_not_parse() {
        let error = require_https_str("not a url", "request_uri").unwrap_err();
        assert_eq!(error.what, "request_uri");
        assert_eq!(error.url, "not a url");
    }

    #[test]
    fn checks_the_request_uri_query_parameter() {
        let ok = Url::parse(
            "openid4vp://?client_id=x&request_uri=https%3A%2F%2Fverifier.example%2Frequest",
        )
        .unwrap();
        assert!(require_https_request_uri(&ok).is_ok());

        let by_value = Url::parse("openid4vp://?client_id=x&request=eyJ").unwrap();
        assert!(require_https_request_uri(&by_value).is_ok());

        let insecure =
            Url::parse("openid4vp://?client_id=x&request_uri=http%3A%2F%2Fevil.example%2Frequest")
                .unwrap();
        let error = require_https_request_uri(&insecure).unwrap_err();
        assert_eq!(error.what, "request_uri");
        assert_eq!(error.url, "http://evil.example/request");
    }

    const DEV_HTTP_URLS: [&str; 11] = [
        "http://127.0.0.1:8080/cb",
        "http://127.1.2.3/cb",
        "http://[::1]:8080/cb",
        "http://localhost/cb",
        "http://LOCALHOST:3000/cb",
        "http://10.0.2.2:8080/cb",
        "http://172.16.5.4/cb",
        "http://172.31.255.255/cb",
        "http://192.168.1.10/cb",
        "http://[fd00::1]/cb",
        "http://[fc00::1]/cb",
    ];

    #[test]
    fn refuses_http_to_dev_hosts_by_default() {
        for url in DEV_HTTP_URLS {
            let url = Url::parse(url).unwrap();
            assert!(check(&url, "response_uri", false).is_err(), "{url}");
        }
    }

    #[test]
    fn accepts_http_to_dev_hosts_when_allowed() {
        for url in DEV_HTTP_URLS {
            let url = Url::parse(url).unwrap();
            assert!(check(&url, "response_uri", true).is_ok(), "{url}");
        }
    }

    #[tokio::test]
    async fn the_opt_in_reaches_the_exported_check() {
        let url = "http://10.0.2.2:8080/cb".to_string();
        {
            let _dev = DevHttp::set(false).await;
            assert!(!is_allowed_request_url(url.clone()));
            assert!(is_allowed_request_url("https://verifier.example/cb".into()));
        }
        {
            let _dev = DevHttp::set(true).await;
            assert!(is_allowed_request_url(url.clone()));
            assert!(!is_allowed_request_url("http://evil.example/cb".into()));
            assert!(!is_allowed_request_url("not a url".into()));
        }
    }
}
