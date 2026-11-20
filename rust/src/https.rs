//! The https-only rule for URLs that the Rust layer contacts.

use std::net::{Ipv4Addr, Ipv6Addr};

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

/// Refuse a URL that is not `https`.
///
/// The rule:
///
/// - The scheme `https` is accepted.
/// - The scheme `http` is accepted only in debug builds
///   (`cfg(debug_assertions)`), and only when the host is a loopback or
///   private LAN address: the domain `localhost`, IPv4 `127.0.0.0/8`,
///   `10.0.0.0/8`, `172.16.0.0/12` and `192.168.0.0/16`, IPv6 `::1` and
///   `fc00::/7`. This lets developers use a local verifier, the Android
///   emulator host alias `10.0.2.2`, or a phone on a dev LAN. The host is
///   checked as a parsed [`Host`], so a domain name such as
///   `localhost.evil.example` or `dev.local` is refused.
/// - Every other scheme is refused.
///
/// Why: a verifier controls `request_uri` and `response_uri`. Over plain
/// `http`, a network attacker can read the `vp_token` that the wallet sends,
/// and change the request that the wallet fetches. The consent screen also
/// shows the `response_uri` host as the verifier, so that host must be
/// authenticated by TLS.
///
/// `what` names the URL in the error, for example `response_uri`.
pub(crate) fn require_https(url: &Url, what: &str) -> Result<(), InsecureUrl> {
    match url.scheme() {
        "https" => Ok(()),
        "http" if cfg!(debug_assertions) && url.host().is_some_and(is_dev_host) => Ok(()),
        _ => Err(InsecureUrl {
            what: what.to_string(),
            url: url.to_string(),
        }),
    }
}

/// Parse `url` and apply [`require_https`] to it. A URL that does not parse is
/// refused too.
pub(crate) fn require_https_str(url: &str, what: &str) -> Result<(), InsecureUrl> {
    let refused = || InsecureUrl {
        what: what.to_string(),
        url: url.to_string(),
    };
    require_https(&Url::parse(url).map_err(|_| refused())?, what)
}

/// Apply [`require_https`] to every `request_uri` query parameter of an
/// authorization request URL, before the wallet fetches it.
pub(crate) fn require_https_request_uri(authorization_request: &Url) -> Result<(), InsecureUrl> {
    authorization_request
        .query_pairs()
        .filter(|(name, _)| name == "request_uri")
        .try_for_each(|(_, request_uri)| require_https_str(&request_uri, "request_uri"))
}

/// Return true for a loopback or private LAN host.
fn is_dev_host(host: Host<&str>) -> bool {
    match host {
        Host::Domain(domain) => domain.eq_ignore_ascii_case("localhost"),
        Host::Ipv4(ip) => is_dev_ipv4(ip),
        Host::Ipv6(ip) => is_dev_ipv6(ip),
    }
}

/// `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12` or `192.168.0.0/16`.
fn is_dev_ipv4(ip: Ipv4Addr) -> bool {
    ip.is_loopback() || ip.is_private()
}

/// `::1` or the unique local range `fc00::/7`.
fn is_dev_ipv6(ip: Ipv6Addr) -> bool {
    ip.is_loopback() || (ip.segments()[0] & 0xfe00) == 0xfc00
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(url: &str) -> Result<(), InsecureUrl> {
        require_https(&Url::parse(url).unwrap(), "response_uri")
    }

    #[test]
    fn accepts_https() {
        assert!(check("https://verifier.example/cb").is_ok());
        assert!(check("https://127.0.0.1:8443/cb").is_ok());
        assert!(check("https://10.0.2.2/cb").is_ok());
    }

    #[test]
    fn refuses_http_to_a_public_host() {
        let error = check("http://evil.example/cb").unwrap_err();
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
            assert!(check(url).is_err(), "{url} should be refused");
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
            assert!(check(url).is_err(), "{url} should be refused");
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

    #[cfg(debug_assertions)]
    #[test]
    fn accepts_http_to_loopback_and_lan_in_debug_builds() {
        for url in DEV_HTTP_URLS {
            assert!(check(url).is_ok(), "{url} should be accepted in debug");
            assert!(require_https_str(url, "request URL").is_ok());
        }
    }

    #[cfg(not(debug_assertions))]
    #[test]
    fn refuses_http_to_loopback_and_lan_in_release_builds() {
        for url in DEV_HTTP_URLS {
            assert!(check(url).is_err(), "{url} should be refused in release");
        }
    }
}
