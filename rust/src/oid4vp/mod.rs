pub mod dc_api;
pub mod draft18;
pub mod dynamic_credential;
pub mod error;
pub mod facade;
pub mod holder;
pub mod iso_18013_7;
pub mod permission_request;
pub mod presentation;
pub mod verifier;

use serde_json::Value;
use url::Url;

pub use dynamic_credential::*;
pub use facade::*;
pub use holder::*;
pub use permission_request::*;
pub use presentation::*;
pub use verifier::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Oid4vpVersion {
    V1,
    Draft18,
    /// OpenID4VP draft 13 (the pre-`client_id_scheme`, Presentation-Exchange
    /// era whose cross-device flow uses the bare `post` response mode and
    /// delivers to `redirect_uri`). Served by translating the request onto the
    /// draft-18 engine — see `facade::draft13_request_to_draft18`.
    Draft13,
    Unsupported,
}

#[uniffi::export]
pub fn get_oid4vp_version(request: String) -> Oid4vpVersion {
    // Draft 13 is the ONLY version that uses the bare `post` response mode
    // (draft 18 and v1 both use `direct_post`/`direct_post.jwt`). A request that
    // carries `response_mode=post` by value is therefore unambiguously draft 13,
    // and must be classified before the Presentation-Exchange / `request_uri`
    // heuristics below would otherwise route it to draft 18. A draft-13 request
    // delivered purely by `request_uri` (no inline `response_mode`) cannot be
    // auto-detected here — callers force `Oid4vpVersion::Draft13` for that
    // transport, mirroring the existing draft-18 `request_uri` limitation.
    if response_mode_is_post(&request) {
        return Oid4vpVersion::Draft13;
    }

    let has_dcql = contains_oid4vp_parameter(&request, "dcql_query");
    let has_draft18_definition = contains_oid4vp_parameter(&request, "presentation_definition")
        || contains_oid4vp_parameter(&request, "presentation_definition_uri");
    let has_request_uri = contains_oid4vp_parameter(&request, "request_uri");

    if has_dcql && client_id_is_v1_compatible(&request) {
        return Oid4vpVersion::V1;
    }

    if has_request_uri && client_id_is_v1_compatible(&request) {
        return Oid4vpVersion::V1;
    }

    if has_draft18_definition || has_dcql {
        return Oid4vpVersion::Draft18;
    }

    if has_request_uri && request_uri_indicates_draft18(&request) {
        return Oid4vpVersion::Draft18;
    }

    Oid4vpVersion::Unsupported
}

/// True iff the request carries a `response_mode` whose value is exactly `post`
/// (the draft-13 cross-device response mode). Deliberately exact-matches `post`
/// so it never fires on draft-18 / v1 `direct_post` (which merely *contains* the
/// substring). Reads the query parameter, the top-level JSON field, and the
/// `request=`-nested object via [`extract_query_parameter`]/[`extract_json_string`].
fn response_mode_is_post(request: &str) -> bool {
    candidate_request_strings(request).iter().any(|candidate| {
        let mode = extract_query_parameter(candidate, "response_mode")
            .or_else(|| extract_json_string(candidate, "response_mode"));
        mode.as_deref() == Some("post")
    })
}

fn contains_oid4vp_parameter(request: &str, parameter: &str) -> bool {
    candidate_request_strings(request).iter().any(|candidate| {
        query_contains_parameter(candidate, parameter)
            || json_contains_parameter(candidate, parameter)
            || candidate.contains(&format!("{parameter}="))
            || candidate.contains(&format!("\"{parameter}\""))
    })
}

fn candidate_request_strings(request: &str) -> Vec<String> {
    let normalized = request.replace("authorize", "");
    match urlencoding::decode(&normalized) {
        Ok(decoded) if decoded != normalized => vec![normalized.clone(), decoded.into_owned()],
        _ => vec![normalized],
    }
}

fn client_id_is_v1_compatible(request: &str) -> bool {
    candidate_request_strings(request)
        .iter()
        .find_map(|candidate| extract_client_id(candidate))
        .map(|client_id| match client_id_prefix(&client_id) {
            None => true,
            Some(prefix) => matches!(
                prefix,
                "redirect_uri"
                    | "decentralized_identifier"
                    | "x509_san_dns"
                    | "x509_hash"
                    | "openid_federation"
                    | "verifier_attestation"
            ),
        })
        .unwrap_or(true)
}

fn extract_client_id(request: &str) -> Option<String> {
    extract_query_parameter(request, "client_id")
        .or_else(|| extract_json_string(request, "client_id"))
}

fn extract_query_parameter(request: &str, parameter: &str) -> Option<String> {
    let components = Url::parse(request).ok()?;

    if let Some(value) = components
        .query_pairs()
        .find(|(name, _)| name.as_ref() == parameter)
        .map(|(_, value)| value.into_owned())
    {
        return Some(value);
    }

    let request_value = components
        .query_pairs()
        .find(|(name, _)| name.as_ref() == "request")
        .map(|(_, value)| value.into_owned())?;

    extract_json_string(&request_value, parameter)
}

fn request_uri_indicates_draft18(request: &str) -> bool {
    candidate_request_strings(request).iter().any(|candidate| {
        extract_query_parameter(candidate, "request_uri")
            .map(|request_uri| {
                request_uri.contains("OID4VP-draft18")
                    || request_uri.contains("profile=OID4VP-draft18")
            })
            .unwrap_or(false)
    }) || (has_raw_legacy_client_id(request) && has_request_uri_parameter(request))
}

fn has_request_uri_parameter(request: &str) -> bool {
    candidate_request_strings(request)
        .iter()
        .any(|candidate| query_contains_parameter(candidate, "request_uri"))
}

fn has_raw_legacy_client_id(request: &str) -> bool {
    candidate_request_strings(request)
        .iter()
        .find_map(|candidate| extract_client_id(candidate))
        .map(|client_id| matches!(client_id_prefix(&client_id), Some("did" | "https")))
        .unwrap_or(false)
}

fn extract_json_string(request: &str, parameter: &str) -> Option<String> {
    let json = serde_json::from_str::<Value>(request).ok()?;
    json.get(parameter)?.as_str().map(ToOwned::to_owned)
}

fn client_id_prefix(client_id: &str) -> Option<&str> {
    client_id.split_once(':').map(|(prefix, _)| prefix)
}

fn query_contains_parameter(request: &str, parameter: &str) -> bool {
    let Ok(components) = Url::parse(request) else {
        return false;
    };

    if components
        .query_pairs()
        .any(|(name, _)| name.as_ref() == parameter)
    {
        return true;
    }

    let Some(request_value) = components
        .query_pairs()
        .find(|(name, _)| name.as_ref() == "request")
        .map(|(_, value)| value.into_owned())
    else {
        return false;
    };

    json_contains_parameter(&request_value, parameter)
}

fn json_contains_parameter(request: &str, parameter: &str) -> bool {
    let Ok(json) = serde_json::from_str::<Value>(request) else {
        return false;
    };

    json.get(parameter).is_some()
}

#[cfg(test)]
mod tests {
    use super::{get_oid4vp_version, Oid4vpVersion};

    #[test]
    fn get_oid4vp_version_prefers_v1_when_both_shapes_are_present() {
        let request = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcallback&dcql_query=%7B%7D&presentation_definition=%7B%7D";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::V1);
    }

    #[test]
    fn get_oid4vp_version_detects_v1_requests() {
        let request = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcallback&dcql_query=%7B%22credentials%22%3A%5B%5D%7D";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::V1);
    }

    #[test]
    fn get_oid4vp_version_detects_v1_request_uri_shapes() {
        let request = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcallback&request_uri=https%3A%2F%2Fwallet.example%2Frequest.jwt";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::V1);
    }

    #[test]
    fn get_oid4vp_version_detects_draft18_requests() {
        let request =
            "openid4vp://?client_id=test&presentation_definition=%7B%22input_descriptors%22%3A%5B%5D%7D";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_detects_draft18_request_objects() {
        let request = r#"{"client_id":"did:web:example.com","presentation_definition":{"input_descriptors":[]}}"#;
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_detects_draft18_request_uri_profiles() {
        let request = "openid4vp://?client_id=did%3Aweb%3Auat-credentials.dmv.ca.gov&request_uri=https%3A%2F%2Fuat-credentials.dmv.ca.gov%2Fopenid%2Fclient%2Fauthorization%2Frequest%3Fprofile%3DOID4VP-draft18";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_detects_legacy_request_uri_shapes_by_client_id() {
        let request = "openid4vp://?client_id=did%3Aweb%3Aexample.com&request_uri=https%3A%2F%2Fexample.com%2Frequest.jwt";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_routes_dcql_requests_with_raw_did_client_ids_to_draft18() {
        let request = r#"{"client_id":"did:web:example.com","dcql_query":{"credentials":[]}}"#;
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_routes_dcql_requests_with_raw_https_client_ids_to_draft18() {
        let request =
            r#"{"client_id":"https://wallet.example/callback","dcql_query":{"credentials":[]}}"#;
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_returns_unsupported_for_unknown_shapes() {
        let request = "openid4vp://?client_id=test&nonce=123";
        assert_eq!(
            get_oid4vp_version(request.into()),
            Oid4vpVersion::Unsupported
        );
    }

    #[test]
    fn get_oid4vp_version_detects_draft13_by_post_response_mode() {
        // Bare `response_mode=post` is the unambiguous draft-13 marker — even
        // though presentation_definition would otherwise route to draft 18.
        let request = "openid4vp://?client_id=https%3A%2F%2Fclient.example.org%2Fcb&response_mode=post&redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb&presentation_definition=%7B%22input_descriptors%22%3A%5B%5D%7D&nonce=n-0S6";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft13);
    }

    #[test]
    fn get_oid4vp_version_detects_draft13_from_request_object() {
        let request = r#"{"client_id":"https://client.example.org/post","redirect_uri":"https://client.example.org/post","response_type":"vp_token","response_mode":"post","presentation_definition":{"input_descriptors":[]},"nonce":"n-0S6"}"#;
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft13);
    }

    #[test]
    fn get_oid4vp_version_does_not_confuse_direct_post_with_post() {
        // `direct_post` must NOT be misread as the draft-13 bare `post`.
        let request = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcb&response_mode=direct_post&dcql_query=%7B%7D";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::V1);
    }
}
