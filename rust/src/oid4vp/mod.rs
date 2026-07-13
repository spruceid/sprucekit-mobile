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
    select_oid4vp_version(&request, &[])
}

/// Classify an OID4VP request, restricting the result to the `supported`
/// versions. An empty slice means "any version" (the legacy auto-detection). A
/// non-empty slice excludes every version not listed, so a version the
/// integrator does not support is never selected — and never gets to consume a
/// single-use `request_uri` on a wrong-version fetch. The heuristics' relative
/// priority is unchanged; `supported` only gates which branch may fire.
///
/// A slice naming exactly one version is an explicit caller override (the
/// pre-0.17 `Oid4vpCompatibilityMode::{V1,Draft18}` behavior): that version is
/// returned directly, without requiring the shape heuristics to independently
/// re-confirm it. With only one version permitted there is nothing to
/// disambiguate, and forcing it preserves integrations that select a single
/// version for request shapes the heuristics do not recognize (e.g. a Draft 18
/// `request_uri` whose `client_id` uses the `redirect_uri:` prefix and whose
/// `request_uri` carries no `OID4VP-draft18` marker, which would otherwise fall
/// through to [`Oid4vpVersion::Unsupported`]).
pub(crate) fn select_oid4vp_version(request: &str, supported: &[Oid4vpVersion]) -> Oid4vpVersion {
    if let [only] = supported {
        return *only;
    }

    let allowed = |version| supported.is_empty() || supported.contains(&version);

    // Draft 13 is the only version using the bare `post` response mode (draft 18
    // and v1 use `direct_post`/`direct_post.jwt`), so it is unambiguously draft 13
    // and is classified before the Presentation-Exchange / `request_uri`
    // heuristics that would otherwise route it to draft 18.
    if allowed(Oid4vpVersion::Draft13) && response_mode_is_post(request) {
        return Oid4vpVersion::Draft13;
    }

    let has_dcql = contains_oid4vp_parameter(request, "dcql_query");
    let has_draft18_definition = contains_oid4vp_parameter(request, "presentation_definition")
        || contains_oid4vp_parameter(request, "presentation_definition_uri");
    let has_request_uri = contains_oid4vp_parameter(request, "request_uri");

    if allowed(Oid4vpVersion::V1) && has_dcql && client_id_is_v1_compatible(request) {
        return Oid4vpVersion::V1;
    }

    if allowed(Oid4vpVersion::V1) && has_request_uri && client_id_is_v1_compatible(request) {
        return Oid4vpVersion::V1;
    }

    if allowed(Oid4vpVersion::Draft18) && (has_draft18_definition || has_dcql) {
        return Oid4vpVersion::Draft18;
    }

    if allowed(Oid4vpVersion::Draft18) && has_request_uri && request_uri_indicates_draft18(request)
    {
        return Oid4vpVersion::Draft18;
    }

    // Draft 18 excluded: a Presentation-Exchange- or `request_uri`-shaped request
    // can only be draft 13, the other PE version. This is what routes a
    // bare-`request_uri` draft-13 request correctly for an integrator who
    // supports draft 13 but not draft 18.
    if allowed(Oid4vpVersion::Draft13) && (has_draft18_definition || has_request_uri) {
        return Oid4vpVersion::Draft13;
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
    use super::{get_oid4vp_version, select_oid4vp_version, Oid4vpVersion};

    // A minimal draft-13 cross-device link: only `client_id` + `request_uri` in
    // the outer link. The discriminating `response_mode=post` lives inside the
    // object fetched from `request_uri`, so detection cannot see it here.
    const DRAFT13_REQUEST_URI: &str = "openid4vp://?client_id=https%3A%2F%2Fverifier.example%2Fcb&request_uri=https%3A%2F%2Fverifier.example%2Frequest%2Fabc";

    #[test]
    fn select_empty_supported_misroutes_bare_draft13_request_uri_to_draft18() {
        // Documents the motivating limitation: with no supported set (auto), a
        // bare-`request_uri` draft-13 request is misclassified as draft 18 (the
        // wrong-version fetch then consumes the single-use `request_uri`).
        assert_eq!(
            select_oid4vp_version(DRAFT13_REQUEST_URI, &[]),
            Oid4vpVersion::Draft18
        );
    }

    #[test]
    fn select_routes_bare_draft13_request_uri_to_draft13_when_draft18_excluded() {
        // The fix: excluding draft 18 from the supported set makes the same
        // request resolve to draft 13, so the correct flow does the single fetch.
        assert_eq!(
            select_oid4vp_version(DRAFT13_REQUEST_URI, &[Oid4vpVersion::Draft13]),
            Oid4vpVersion::Draft13
        );
        assert_eq!(
            select_oid4vp_version(
                DRAFT13_REQUEST_URI,
                &[Oid4vpVersion::V1, Oid4vpVersion::Draft13]
            ),
            Oid4vpVersion::Draft13
        );
    }

    #[test]
    fn select_distinguishes_v1_and_draft18_within_supported_set() {
        let supported = [Oid4vpVersion::V1, Oid4vpVersion::Draft18];

        let v1 = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcb&dcql_query=%7B%22credentials%22%3A%5B%5D%7D";
        assert_eq!(select_oid4vp_version(v1, &supported), Oid4vpVersion::V1);

        let draft18 = "openid4vp://?client_id=test&presentation_definition=%7B%22input_descriptors%22%3A%5B%5D%7D";
        assert_eq!(
            select_oid4vp_version(draft18, &supported),
            Oid4vpVersion::Draft18
        );
    }

    #[test]
    fn select_forces_the_sole_supported_version_even_when_the_shape_disagrees() {
        // A single supported version is an explicit caller override: it is
        // honored even for a request the heuristics would otherwise classify as
        // a different version. Here a v1-shaped request with only draft 13
        // supported is forced to draft 13 (pre-0.17 forced-mode behavior).
        let v1 = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcb&dcql_query=%7B%22credentials%22%3A%5B%5D%7D";
        assert_eq!(
            select_oid4vp_version(v1, &[Oid4vpVersion::Draft13]),
            Oid4vpVersion::Draft13
        );
    }

    #[test]
    fn select_returns_unsupported_when_no_permitted_version_matches() {
        // With more than one version permitted the heuristics still run, so an
        // unrecognized shape resolves to Unsupported (the override shortcut only
        // applies to a single supported version).
        let unknown = "openid4vp://?client_id=test&nonce=123";
        assert_eq!(
            select_oid4vp_version(unknown, &[Oid4vpVersion::V1, Oid4vpVersion::Draft18]),
            Oid4vpVersion::Unsupported
        );
    }

    #[test]
    fn select_forces_draft18_for_redirect_uri_prefixed_request_uri_shape() {
        // Regression (Veres sandbox): a Draft 18 request whose `client_id` uses
        // the `redirect_uri:` prefix and whose bare `request_uri` carries no
        // `OID4VP-draft18` marker is not recognized by the request_uri
        // heuristics. With Draft 18 as the sole supported version it must still
        // be forced to Draft 18 rather than resolving to Unsupported — which had
        // surfaced as Oid4vpFacadeError::UnsupportedRequest before the holder
        // could even be built.
        let veres = "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fsandbox.platform.veres.dev%2Fworkflows%2Fz1A6xHHmw9xpm2CZjfPoN9WPP%2Fexchanges%2Fz1ABrtc3z2aDM7y1VYfCo2nek%2Fopenid%2Fclients%2Fdefault%2Fauthorization%2Fresponse&request_uri=https%3A%2F%2Fsandbox.platform.veres.dev%2Fworkflows%2Fz1A6xHHmw9xpm2CZjfPoN9WPP%2Fexchanges%2Fz1ABrtc3z2aDM7y1VYfCo2nek%2Fopenid%2Fclients%2Fdefault%2Fauthorization%2Frequest&request_uri_method=post";
        assert_eq!(
            select_oid4vp_version(veres, &[Oid4vpVersion::Draft18]),
            Oid4vpVersion::Draft18
        );
        // In auto mode (no restriction) the same request still routes to V1 via
        // its v1-compatible `redirect_uri:` client_id — the app-side mitigation.
        assert_eq!(select_oid4vp_version(veres, &[]), Oid4vpVersion::V1);
    }

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
