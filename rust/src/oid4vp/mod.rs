pub mod dc_api;
pub mod draft18;
pub mod error;
pub mod holder;
pub mod iso_18013_7;
pub mod permission_request;
pub mod presentation;
pub mod verifier;

use serde_json::Value;
use url::Url;

pub use holder::*;
pub use permission_request::*;
pub use presentation::*;
pub use verifier::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Oid4vpVersion {
    V1,
    Draft18,
    Unsupported,
}

#[uniffi::export]
pub fn get_oid4vp_version(request: String) -> Oid4vpVersion {
    if contains_oid4vp_parameter(&request, "dcql_query") {
        return Oid4vpVersion::V1;
    }

    if contains_oid4vp_parameter(&request, "presentation_definition")
        || contains_oid4vp_parameter(&request, "presentation_definition_uri")
    {
        return Oid4vpVersion::Draft18;
    }

    Oid4vpVersion::Unsupported
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
        let request = "openid4vp://?client_id=test&dcql_query=%7B%7D&presentation_definition=%7B%7D";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::V1);
    }

    #[test]
    fn get_oid4vp_version_detects_v1_requests() {
        let request =
            "openid4vp://?client_id=test&dcql_query=%7B%22credentials%22%3A%5B%5D%7D";
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
        let request =
            r#"{"client_id":"did:web:example.com","presentation_definition":{"input_descriptors":[]}}"#;
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Draft18);
    }

    #[test]
    fn get_oid4vp_version_returns_unsupported_for_unknown_shapes() {
        let request = "openid4vp://?client_id=test&nonce=123";
        assert_eq!(get_oid4vp_version(request.into()), Oid4vpVersion::Unsupported);
    }
}
