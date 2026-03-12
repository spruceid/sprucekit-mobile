use isomdl::{
    definitions::x509::trust_anchor::{TrustAnchor, TrustAnchorRegistry, TrustPurpose},
    vical::{ValidationOptions, VerifiedVical, Vical},
};
use x509_cert::{
    der::{Decode as _, DecodePem as _, EncodePem as _},
    Certificate,
};

use crate::trusted_roots;

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum VicalError {
    #[error("Network error: {value}")]
    Network { value: String },
    #[error("Parse error: {value}")]
    Parse { value: String },
    #[error("Scraping error: {value}")]
    Scraping { value: String },
    #[error("{value}")]
    Generic { value: String },
}

#[derive(uniffi::Record)]
pub struct VicalResult {
    pub vical_bytes: Vec<u8>,
    pub vical_name: Option<String>,
    pub vical_date: Option<String>,
    pub verified: bool,
}

#[derive(uniffi::Record)]
pub struct VicalTrustAnchorsResult {
    pub trust_anchor_pems: Vec<String>,
    pub updated_vical_bytes: Option<Vec<u8>>,
    pub vical_name: Option<String>,
    pub vical_date: Option<String>,
    pub verified: bool,
}

const AAMVA_BASE_URL: &str = "https://vical.dts.aamva.org";
const AAMVA_CURRENT_VICAL_PATH: &str = "/currentVical";
const AAMVA_CA_ROOT_PATH: &str = "/certificates/ca";
const AAMVA_CA_INTERMEDIATE_PATH: &str = "/certificates/ca_intermediate";

/// Scrapes the AAMVA /currentVical HTML page for the VICAL download path and date.
/// Looks for an `href="/vical/vc/..."` link and extracts the date from a table cell.
fn scrape_vical_page(html: &str) -> Result<(String, Option<String>, Option<String>), VicalError> {
    // Find download href like: href="/vical/vc/vc-2026-03-08-1773006950906"
    let download_path = find_vical_download_href(html).ok_or_else(|| VicalError::Scraping {
        value: "could not find VICAL download link in HTML".into(),
    })?;

    // Extract the name from the path (last segment)
    let vical_name = download_path.rsplit('/').next().map(|s| s.to_string());

    // Try to find a date in the page (look for ISO date pattern in table cells)
    let vical_date = find_vical_date(html);

    Ok((download_path, vical_name, vical_date))
}

/// Find the VICAL download href from the HTML page.
/// Looks for href="/vical/vc/..." pattern.
fn find_vical_download_href(html: &str) -> Option<String> {
    let needle = "href=\"/vical/vc/";
    let start = html.find(needle)?;
    let href_start = start + "href=\"".len();
    let rest = &html[href_start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

/// Try to extract a date string from the VICAL page.
/// Looks for a <td> containing an ISO-ish datetime like "2026-03-08T21:55:50.906350".
fn find_vical_date(html: &str) -> Option<String> {
    // Look for <td> elements containing datetime-like strings
    let mut search_from = 0;
    while let Some(td_start) = html[search_from..].find("<td>") {
        let content_start = search_from + td_start + 4;
        if let Some(td_end) = html[content_start..].find("</td>") {
            let content = html[content_start..content_start + td_end].trim();
            // Check if it looks like an ISO date (starts with 4 digits, dash, 2 digits)
            if content.len() >= 10
                && content.as_bytes()[4] == b'-'
                && content.as_bytes()[7] == b'-'
                && content[..4].chars().all(|c| c.is_ascii_digit())
            {
                return Some(content.to_string());
            }
            search_from = content_start + td_end;
        } else {
            break;
        }
    }
    None
}

fn http_get(url: &str) -> Result<Vec<u8>, VicalError> {
    let response = reqwest::blocking::get(url).map_err(|e| VicalError::Network {
        value: format!("HTTP GET {url} failed: {e}"),
    })?;
    let status = response.status();
    if !status.is_success() {
        return Err(VicalError::Network {
            value: format!("HTTP GET {url} returned status {status}"),
        });
    }
    response
        .bytes()
        .map(|b| b.to_vec())
        .map_err(|e| VicalError::Network {
            value: format!("failed to read response body from {url}: {e}"),
        })
}

fn http_get_text(url: &str) -> Result<String, VicalError> {
    let response = reqwest::blocking::get(url).map_err(|e| VicalError::Network {
        value: format!("HTTP GET {url} failed: {e}"),
    })?;
    let status = response.status();
    if !status.is_success() {
        return Err(VicalError::Network {
            value: format!("HTTP GET {url} returned status {status}"),
        });
    }
    response.text().map_err(|e| VicalError::Network {
        value: format!("failed to read response text from {url}: {e}"),
    })
}

/// Build a TrustAnchorRegistry for verifying the AAMVA VICAL signature.
///
/// Tries to fetch fresh certificates from AAMVA endpoints; falls back to
/// hardcoded DER files bundled in the binary.
fn build_aamva_vical_trust_anchors() -> TrustAnchorRegistry {
    let root_cert = fetch_aamva_cert(AAMVA_CA_ROOT_PATH).unwrap_or_else(|e| {
        tracing::warn!("failed to fetch AAMVA root CA, using hardcoded: {e}");
        trusted_roots::load_aamva_ca_root_certificate()
            .expect("hardcoded AAMVA root CA must be valid")
    });

    // The intermediate is included in the COSE_Sign1 x5chain, so we only
    // need the root in the trust anchor registry.  We still fetch the
    // intermediate to keep it available if needed in the future.
    let _intermediate = fetch_aamva_cert(AAMVA_CA_INTERMEDIATE_PATH).unwrap_or_else(|e| {
        tracing::warn!("failed to fetch AAMVA intermediate CA, using hardcoded: {e}");
        trusted_roots::load_aamva_ca_intermediate_certificate()
            .expect("hardcoded AAMVA intermediate CA must be valid")
    });

    TrustAnchorRegistry {
        anchors: vec![TrustAnchor {
            certificate: root_cert,
            purpose: TrustPurpose::VicalAuthority,
        }],
    }
}

/// Fetch a PEM certificate from an AAMVA endpoint and parse it.
fn fetch_aamva_cert(path: &str) -> Result<Certificate, VicalError> {
    let url = format!("{AAMVA_BASE_URL}{path}");
    let bytes = http_get(&url)?;
    // The endpoint may return PEM or DER. Try DER first, then PEM.
    Certificate::from_der(&bytes).or_else(|_| {
        let pem_str = std::str::from_utf8(&bytes).map_err(|e| VicalError::Parse {
            value: format!("certificate response is not valid UTF-8: {e}"),
        })?;
        Certificate::from_pem(pem_str).map_err(|e| VicalError::Parse {
            value: format!("failed to parse certificate from {url}: {e}"),
        })
    })
}

/// Attempt verified parse, falling back to unverified parse.
/// Returns (Vical, verified_flag).
fn verify_or_parse_vical(bytes: &[u8]) -> Result<(Vical, bool), VicalError> {
    // Build AAMVA trust anchors for signature verification
    let trust_anchors = build_aamva_vical_trust_anchors();
    let options = ValidationOptions::default();

    // Try verified parse (async, use block_on)
    match crate::mdl::block_on(VerifiedVical::from_bytes_with_options(
        bytes,
        &trust_anchors,
        &(),
        &options,
    )) {
        Ok(verified) => {
            tracing::info!("VICAL signature verified successfully");
            Ok((verified.vical, true))
        }
        Err(e) => {
            tracing::warn!("VICAL verification failed, falling back to unverified parse: {e}");
            // Fall back to unverified parse
            let parsed = Vical::parse(bytes).map_err(|e| VicalError::Parse {
                value: format!("failed to parse VICAL: {e}"),
            })?;
            Ok((parsed.vical, false))
        }
    }
}

/// Convert a TrustAnchorRegistry's certificates to PEM strings.
fn trust_anchors_to_pems(registry: &TrustAnchorRegistry) -> Vec<String> {
    registry
        .anchors
        .iter()
        .filter_map(
            |anchor| match anchor.certificate.to_pem(Default::default()) {
                Ok(pem) => Some(pem),
                Err(e) => {
                    tracing::warn!("failed to encode trust anchor as PEM: {e}");
                    None
                }
            },
        )
        .collect()
}

/// Merge VICAL-derived PEM strings with additional IACA PEMs.
fn merge_pems(vical_pems: Vec<String>, additional_pems: Option<Vec<String>>) -> Vec<String> {
    let mut result = vical_pems;
    if let Some(extras) = additional_pems {
        result.extend(extras);
    }
    result
}

/// Fetch the latest VICAL from AAMVA.
///
/// Returns raw CBOR bytes + metadata for the app to cache.
/// Does NOT parse or verify — just fetches.
#[uniffi::export]
pub fn fetch_vical() -> Result<VicalResult, VicalError> {
    // 1. Fetch the /currentVical HTML page
    let html_url = format!("{AAMVA_BASE_URL}{AAMVA_CURRENT_VICAL_PATH}");
    let html = http_get_text(&html_url)?;

    // 2. Scrape for download URL and metadata
    let (download_path, vical_name, vical_date) = scrape_vical_page(&html)?;

    // 3. Download the VICAL binary
    let download_url = format!("{AAMVA_BASE_URL}{download_path}");
    let vical_bytes = http_get(&download_url)?;

    Ok(VicalResult {
        vical_bytes,
        vical_name,
        vical_date,
        verified: false, // Not verified yet — just fetched
    })
}

/// Build a trust anchor registry from cached VICAL bytes and/or additional IACA PEMs.
///
/// Tries verified parse of the VICAL, falls back to unverified parse.
/// Merges VICAL-derived anchors with any additional IACA PEM strings.
/// Returns PEM strings compatible with `establish_session`.
#[uniffi::export]
pub fn build_trust_anchor_registry_from_vical(
    vical_bytes: Option<Vec<u8>>,
    additional_iaca_pems: Option<Vec<String>>,
) -> Result<Vec<String>, VicalError> {
    let mut all_pems = Vec::new();

    if let Some(bytes) = vical_bytes {
        match verify_or_parse_vical(&bytes) {
            Ok((vical, _verified)) => {
                let registry = vical.to_trust_anchor_registry();
                all_pems.extend(trust_anchors_to_pems(&registry));
            }
            Err(e) => {
                tracing::warn!(
                    "failed to parse VICAL bytes, continuing with additional IACAs only: {e}"
                );
            }
        }
    }

    Ok(merge_pems(all_pems, additional_iaca_pems))
}

/// Convenience: fetch fresh VICAL (falling back to cached), verify/parse, merge anchors.
///
/// Returns PEM strings + updated VICAL bytes for caching.
#[uniffi::export]
pub fn fetch_and_build_trust_anchors(
    cached_vical_bytes: Option<Vec<u8>>,
    additional_iaca_pems: Option<Vec<String>>,
) -> Result<VicalTrustAnchorsResult, VicalError> {
    // Try fetching fresh VICAL
    let (vical_bytes, vical_name, vical_date) = match fetch_vical() {
        Ok(result) => (
            Some(result.vical_bytes),
            result.vical_name,
            result.vical_date,
        ),
        Err(e) => {
            tracing::warn!("failed to fetch fresh VICAL, using cached: {e}");
            (cached_vical_bytes.clone(), None, None)
        }
    };

    let effective_bytes = vical_bytes.or(cached_vical_bytes);

    let mut all_pems = Vec::new();
    let mut verified = false;
    let mut final_name = vical_name;
    let mut final_date = vical_date;

    if let Some(ref bytes) = effective_bytes {
        match verify_or_parse_vical(bytes) {
            Ok((vical, v)) => {
                verified = v;
                if final_name.is_none() {
                    final_name = Some(vical.vical_provider.clone());
                }
                if final_date.is_none() {
                    final_date = Some(format!("{:?}", vical.date));
                }
                let registry = vical.to_trust_anchor_registry();
                all_pems.extend(trust_anchors_to_pems(&registry));
            }
            Err(e) => {
                tracing::warn!("failed to parse VICAL, continuing with additional IACAs only: {e}");
            }
        }
    }

    let trust_anchor_pems = merge_pems(all_pems, additional_iaca_pems);

    Ok(VicalTrustAnchorsResult {
        trust_anchor_pems,
        updated_vical_bytes: effective_bytes,
        vical_name: final_name,
        vical_date: final_date,
        verified,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_HTML: &str = r#"
<html><body>
<table>
<tr><td>vc-2026-03-08-1773006950906</td><td>2026-03-08T21:55:50.906350</td><td><a href="/vical/vc/vc-2026-03-08-1773006950906">Download</a></td></tr>
</table>
</body></html>"#;

    #[test]
    fn scrape_download_href() {
        let href = find_vical_download_href(SAMPLE_HTML);
        assert_eq!(
            href.as_deref(),
            Some("/vical/vc/vc-2026-03-08-1773006950906")
        );
    }

    #[test]
    fn scrape_date() {
        let date = find_vical_date(SAMPLE_HTML);
        assert_eq!(date.as_deref(), Some("2026-03-08T21:55:50.906350"));
    }

    #[test]
    fn scrape_full_page() {
        let (path, name, date) = scrape_vical_page(SAMPLE_HTML).unwrap();
        assert_eq!(path, "/vical/vc/vc-2026-03-08-1773006950906");
        assert_eq!(name.as_deref(), Some("vc-2026-03-08-1773006950906"));
        assert_eq!(date.as_deref(), Some("2026-03-08T21:55:50.906350"));
    }

    #[test]
    fn scrape_no_link_returns_error() {
        let html = "<html><body>No links here</body></html>";
        assert!(scrape_vical_page(html).is_err());
    }

    #[test]
    fn scrape_no_date_returns_none() {
        let html = r#"<html><body><a href="/vical/vc/test-123">Download</a></body></html>"#;
        let (_, _, date) = scrape_vical_page(html).unwrap();
        assert!(date.is_none());
    }

    #[test]
    fn merge_pems_combines_sources() {
        let vical = vec!["PEM1".into(), "PEM2".into()];
        let extra = Some(vec!["PEM3".into()]);
        let merged = merge_pems(vical, extra);
        assert_eq!(merged, vec!["PEM1", "PEM2", "PEM3"]);
    }

    #[test]
    fn merge_pems_no_extras() {
        let vical = vec!["PEM1".into()];
        let merged = merge_pems(vical, None);
        assert_eq!(merged, vec!["PEM1"]);
    }

    #[test]
    fn build_trust_anchors_with_no_vical_returns_additional_only() {
        let result =
            build_trust_anchor_registry_from_vical(None, Some(vec!["EXTRA_PEM".into()])).unwrap();
        assert_eq!(result, vec!["EXTRA_PEM"]);
    }

    #[test]
    fn build_trust_anchors_with_invalid_vical_returns_additional_only() {
        let result = build_trust_anchor_registry_from_vical(
            Some(vec![0xFF, 0xFF]),
            Some(vec!["EXTRA_PEM".into()]),
        )
        .unwrap();
        assert_eq!(result, vec!["EXTRA_PEM"]);
    }

    #[test]
    fn hardcoded_aamva_certs_load() {
        trusted_roots::load_aamva_ca_root_certificate().unwrap();
        trusted_roots::load_aamva_ca_intermediate_certificate().unwrap();
    }

    #[test]
    #[ignore]
    fn live_fetch_vical() {
        let result = fetch_vical().unwrap();
        assert!(!result.vical_bytes.is_empty());
        assert!(result.vical_name.is_some());
        println!(
            "Fetched VICAL: name={:?}, date={:?}, {} bytes",
            result.vical_name,
            result.vical_date,
            result.vical_bytes.len()
        );
    }

    #[test]
    #[ignore]
    fn live_fetch_and_build() {
        let result = fetch_and_build_trust_anchors(None, None).unwrap();
        assert!(!result.trust_anchor_pems.is_empty());
        println!(
            "Built {} trust anchors, verified={}, name={:?}, date={:?}",
            result.trust_anchor_pems.len(),
            result.verified,
            result.vical_name,
            result.vical_date
        );
    }

    /// End-to-end test: fetch VICAL, parse it, print every issuing authority
    /// and jurisdiction, then verify the PEMs are accepted by
    /// TrustAnchorRegistry::from_pem_certificates (the same path
    /// establish_session uses).
    #[test]
    #[ignore]
    fn live_fetch_parse_and_list_issuers() {
        use isomdl::definitions::x509::trust_anchor::PemTrustAnchor;

        // 1. Fetch
        let fetched = fetch_vical().unwrap();
        println!(
            "Fetched VICAL: name={:?}, date={:?}, {} bytes",
            fetched.vical_name,
            fetched.vical_date,
            fetched.vical_bytes.len()
        );

        // 2. Parse (via the full verify-or-parse cascade)
        let (vical, verified) = verify_or_parse_vical(&fetched.vical_bytes).unwrap();
        println!("Verified: {verified}");
        println!("Provider: {}", vical.vical_provider);
        println!("Certificate count: {}\n", vical.certificate_infos.len());

        // 3. List every certificate's issuing authority and jurisdiction
        for (i, info) in vical.certificate_infos.iter().enumerate() {
            println!(
                "  [{:>2}] authority={:?}  country={:?}  state={:?}  doctype={:?}",
                i + 1,
                info.issuing_authority.as_deref().unwrap_or("?"),
                info.issuing_country.as_deref().unwrap_or("?"),
                info.state_or_province_name.as_deref().unwrap_or("?"),
                info.doc_type,
            );
        }

        // 4. Convert to PEMs and verify they round-trip through the same
        //    TrustAnchorRegistry path that establish_session uses.
        let registry = vical.to_trust_anchor_registry();
        let pems = trust_anchors_to_pems(&registry);
        assert_eq!(pems.len(), registry.anchors.len());

        let reconstructed = TrustAnchorRegistry::from_pem_certificates(
            pems.iter()
                .map(|pem| PemTrustAnchor {
                    certificate_pem: pem.clone(),
                    purpose: TrustPurpose::Iaca,
                })
                .collect(),
        )
        .expect("PEMs must be accepted by TrustAnchorRegistry::from_pem_certificates");

        assert_eq!(reconstructed.anchors.len(), registry.anchors.len());
        println!(
            "\nAll {} PEMs round-tripped through TrustAnchorRegistry successfully.",
            pems.len()
        );
    }

    /// Test the caching round-trip: fetch once, then use the cached bytes
    /// without network access to build anchors.
    #[test]
    #[ignore]
    fn live_cache_round_trip() {
        // Simulate first launch: fetch fresh
        let first = fetch_and_build_trust_anchors(None, None).unwrap();
        let cached_bytes = first.updated_vical_bytes.clone().unwrap();
        let first_count = first.trust_anchor_pems.len();
        println!(
            "First call: {} anchors, verified={}",
            first_count, first.verified
        );

        // Simulate subsequent launch: pass cached bytes directly
        // (build_trust_anchor_registry_from_vical does no network fetch)
        let second = build_trust_anchor_registry_from_vical(Some(cached_bytes), None).unwrap();
        println!("From cache: {} anchors", second.len());

        assert_eq!(
            first_count,
            second.len(),
            "cached bytes should produce the same anchors"
        );
    }
}
