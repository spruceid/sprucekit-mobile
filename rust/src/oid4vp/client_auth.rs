//! Wallet-side Client Identifier Prefix checks (OID4VP 1.0 §5, §5.9.3) that
//! the `openid4vp` crate leaves to the wallet.
//!
//! Each check binds the request to its `client_id`. Without them, a request
//! can show a trusted verifier's `client_id` and still have the presentation
//! posted to a `response_uri` the attacker controls.
//!
//! The pinned `openid4vp` revisions return early from
//! `verification::did::verify_with_resolver` when `alg` is `none`, skipping
//! the `client_id` match and the signature check. The holders no longer
//! advertise `none`, which makes that check refuse it, and also call
//! [`ensure_signed`] first so the bypass is never reached.

use anyhow::{bail, Context, Result};
use base64::prelude::{Engine as _, BASE64_URL_SAFE_NO_PAD};
use openid4vp::core::authorization_request::{
    parameters::ClientIdScheme, AuthorizationRequestObject,
};
use serde_json::{Map, Value as Json};
use url::Url;

/// Required `typ` of a Request Object (OID4VP 1.0 §5).
const REQUEST_OBJECT_TYP: &str = "oauth-authz-req+jwt";

/// Decode the JOSE header of a compact JWT.
fn jwt_header(jwt: &str) -> Result<Map<String, Json>> {
    let header_b64 = jwt
        .split('.')
        .next()
        .context("Request Object is not a compact JWT")?;
    let header = BASE64_URL_SAFE_NO_PAD
        .decode(header_b64)
        .context("Request Object header is not valid base64url")?;
    serde_json::from_slice(&header).context("Request Object header is not a JSON object")
}

/// The Request Object's `alg` header.
fn jwt_alg(header: &Map<String, Json>) -> Result<&str> {
    header
        .get("alg")
        .and_then(Json::as_str)
        .context("Request Object header has no string 'alg'")
}

/// Whether `alg` marks an unsecured JWT (RFC 7519 §6). JOSE values are
/// case-sensitive, but a mixed-case `None` must not slip through either.
fn is_unsecured(alg: &str) -> bool {
    alg.eq_ignore_ascii_case("none")
}

/// Reject a Request Object whose `typ` is not `oauth-authz-req+jwt`
/// (OID4VP 1.0 §5). The `openid4vp` crate only warns about this for the
/// `redirect_uri` prefix.
pub(crate) fn ensure_request_object_typ(jwt: &str) -> Result<()> {
    let header = jwt_header(jwt)?;
    match header.get("typ").and_then(Json::as_str) {
        Some(REQUEST_OBJECT_TYP) => Ok(()),
        Some(typ) => {
            bail!(
                "Request Object 'typ' is '{typ}', expected '{REQUEST_OBJECT_TYP}' (OID4VP 1.0 §5)"
            )
        }
        None => {
            bail!("Request Object has no 'typ', expected '{REQUEST_OBJECT_TYP}' (OID4VP 1.0 §5)")
        }
    }
}

/// Reject an unsecured (`alg: none`) Request Object. Prefixes that
/// authenticate the verifier by signature (`decentralized_identifier`,
/// `x509_san_dns`, `x509_hash`) require a signed request (§5.9.3), and the
/// `openid4vp` crate returns early on `none` for `decentralized_identifier`.
pub(crate) fn ensure_signed(jwt: &str) -> Result<()> {
    let header = jwt_header(jwt)?;
    if is_unsecured(jwt_alg(&header)?) {
        bail!("Request Object is unsigned ('alg: none'), but this Client Identifier Prefix requires a signed request (OID4VP 1.0 §5.9.3)")
    }
    Ok(())
}

/// The `redirect_uri` prefix (§5.9.3): requests "cannot be signed", and the
/// Client Identifier is the Response URI.
///
/// A signed request carries no trust here, as there is no key to verify it
/// with, so it is refused. An unsigned Request Object must still carry the
/// right `typ` (§5). Unsigned plain parameters (`jwt` is `None`) are allowed.
pub(crate) fn verify_redirect_uri(
    request: &AuthorizationRequestObject,
    jwt: Option<&str>,
) -> Result<()> {
    if let Some(jwt) = jwt {
        ensure_request_object_typ(jwt)?;
        let header = jwt_header(jwt)?;
        let alg = jwt_alg(&header)?;
        if !is_unsecured(alg) {
            bail!("Request Object is signed with '{alg}', but the 'redirect_uri' Client Identifier Prefix does not allow signed requests (OID4VP 1.0 §5.9.3)")
        }
    }

    let client_id = client_id_without_prefix(request, ClientIdScheme::REDIRECT_URI)?;
    let client_uri: Url = client_id
        .parse()
        .with_context(|| format!("'redirect_uri' Client Identifier '{client_id}' is not a URL"))?;

    if &client_uri != request.return_uri() {
        bail!(
            "response URI '{}' does not match the 'redirect_uri' Client Identifier '{client_id}' (OID4VP 1.0 §5.9.3)",
            request.return_uri()
        )
    }

    Ok(())
}

/// The draft-18 `redirect_uri` Client Identifier Scheme (OID4VP draft 18
/// §5.7): "the Authorization Request MUST NOT be signed", and the Client
/// Identifier is the redirect URI, or the response URI for `direct_post`.
///
/// Draft 18 names the scheme in a separate `client_id_scheme` parameter, so
/// the `client_id` is usually the bare URI. A `redirect_uri:` prefix, as some
/// verifiers send, is removed first. Draft 18 does not require a `typ` on
/// Request Objects, so none is checked. Unsigned plain parameters (`jwt` is
/// `None`) are allowed.
pub(crate) fn verify_redirect_uri_draft18(
    request: &openidvp_draft18::core::authorization_request::AuthorizationRequestObject,
    jwt: Option<&str>,
) -> Result<()> {
    use openidvp_draft18::core::authorization_request::parameters::ClientIdScheme;

    if let Some(jwt) = jwt {
        let header = jwt_header(jwt)?;
        let alg = jwt_alg(&header)?;
        if !is_unsecured(alg) {
            bail!("Request Object is signed with '{alg}', but the 'redirect_uri' Client Identifier Scheme does not allow signed requests (OID4VP draft 18 §5.7)")
        }
    }

    let client_id = &request.client_id().context("request has no client_id")?.0;
    let client_id = client_id
        .strip_prefix(ClientIdScheme::REDIRECT_URI)
        .and_then(|rest| rest.strip_prefix(':'))
        .unwrap_or(client_id);
    let client_uri: Url = client_id
        .parse()
        .with_context(|| format!("'redirect_uri' Client Identifier '{client_id}' is not a URL"))?;

    if &client_uri != request.return_uri() {
        bail!(
            "response URI '{}' does not match the 'redirect_uri' Client Identifier '{client_id}' (OID4VP draft 18 §5.7)",
            request.return_uri()
        )
    }

    Ok(())
}

/// The request's `client_id` with `prefix:` removed. Errors when the
/// `client_id` is missing or carries another prefix.
fn client_id_without_prefix<'a>(
    request: &'a AuthorizationRequestObject,
    prefix: &str,
) -> Result<&'a str> {
    let client_id = request.client_id().context("request has no client_id")?;
    client_id
        .0
        .strip_prefix(prefix)
        .and_then(|rest| rest.strip_prefix(':'))
        .with_context(|| {
            format!(
                "client_id '{}' does not have the '{prefix}' prefix",
                client_id.0
            )
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn request(value: Json) -> AuthorizationRequestObject {
        serde_json::from_value(value).expect("invalid request")
    }

    fn direct_post(client_id: &str, response_uri: &str) -> AuthorizationRequestObject {
        request(json!({
            "client_id": client_id,
            "response_uri": response_uri,
            "response_type": "vp_token",
            "response_mode": "direct_post",
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        }))
    }

    fn jwt(header: Json) -> String {
        let header = BASE64_URL_SAFE_NO_PAD.encode(header.to_string());
        let body = BASE64_URL_SAFE_NO_PAD.encode("{}");
        format!("{header}.{body}.")
    }

    #[test]
    fn unsigned_request_objects_are_refused_where_a_signature_is_required() {
        assert!(ensure_signed(&jwt(json!({ "alg": "none" }))).is_err());
        assert!(ensure_signed(&jwt(json!({ "alg": "None" }))).is_err());
        assert!(ensure_signed(&jwt(json!({}))).is_err());
        assert!(ensure_signed(&jwt(json!({ "alg": "ES256" }))).is_ok());
    }

    #[test]
    fn request_object_typ_is_enforced() {
        assert!(ensure_request_object_typ(&jwt(json!({ "typ": REQUEST_OBJECT_TYP }))).is_ok());
        assert!(ensure_request_object_typ(&jwt(json!({ "typ": "JWT" }))).is_err());
        assert!(ensure_request_object_typ(&jwt(json!({}))).is_err());
    }

    #[test]
    fn redirect_uri_client_id_must_be_the_response_uri() {
        let bound = direct_post(
            "redirect_uri:https://verifier.example/post",
            "https://verifier.example/post",
        );
        assert!(verify_redirect_uri(&bound, None).is_ok());

        let unbound = direct_post(
            "redirect_uri:https://verifier.example/post",
            "https://attacker.example/post",
        );
        assert!(verify_redirect_uri(&unbound, None).is_err());

        // Same host, another path.
        let other_path = direct_post(
            "redirect_uri:https://verifier.example/post",
            "https://verifier.example/other",
        );
        assert!(verify_redirect_uri(&other_path, None).is_err());
    }

    #[test]
    fn redirect_uri_request_objects_must_be_unsigned_and_typed() {
        let bound = direct_post(
            "redirect_uri:https://verifier.example/post",
            "https://verifier.example/post",
        );
        let unsigned = jwt(json!({ "alg": "none", "typ": REQUEST_OBJECT_TYP }));
        assert!(verify_redirect_uri(&bound, Some(&unsigned)).is_ok());

        let untyped = jwt(json!({ "alg": "none" }));
        assert!(verify_redirect_uri(&bound, Some(&untyped)).is_err());

        let signed = jwt(json!({ "alg": "ES256", "typ": REQUEST_OBJECT_TYP }));
        assert!(verify_redirect_uri(&bound, Some(&signed)).is_err());
    }

    #[test]
    fn the_prefix_must_match() {
        let request = direct_post("x509_hash:abc", "https://verifier.example/post");
        assert!(verify_redirect_uri(&request, None).is_err());
    }

    fn direct_post_jwt(client_id: &str, response_uri: &str) -> AuthorizationRequestObject {
        request(json!({
            "client_id": client_id,
            "response_uri": response_uri,
            "response_type": "vp_token",
            "response_mode": "direct_post.jwt",
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        }))
    }

    #[test]
    fn direct_post_jwt_requests_are_bound_to_the_response_uri() {
        let bound = direct_post_jwt(
            "redirect_uri:https://verifier.example/post",
            "https://verifier.example/post",
        );
        assert_eq!(bound.return_uri().as_str(), "https://verifier.example/post");
        assert!(verify_redirect_uri(&bound, None).is_ok());

        let unbound = direct_post_jwt(
            "redirect_uri:https://verifier.example/post",
            "https://attacker.example/post",
        );
        assert!(verify_redirect_uri(&unbound, None).is_err());
    }

    /// The crate refuses `redirect_uri` next to `response_uri` while parsing
    /// the request (`AuthorizationRequestObject::try_from`), before any
    /// Client Identifier Prefix check runs.
    #[rstest::rstest]
    #[case::direct_post("direct_post")]
    #[case::direct_post_jwt("direct_post.jwt")]
    fn a_request_with_both_redirect_uri_and_response_uri_does_not_parse(#[case] mode: &str) {
        let err = serde_json::from_value::<AuthorizationRequestObject>(json!({
            "client_id": "redirect_uri:https://verifier.example/post",
            "redirect_uri": "https://verifier.example/post",
            "response_uri": "https://attacker.example/post",
            "response_type": "vp_token",
            "response_mode": mode,
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        }))
        .expect_err("redirect_uri and response_uri together must not parse");
        assert!(err.to_string().contains("mutually exclusive"), "{err}");
    }

    /// For the DC API modes the crate has no response URI and `return_uri()`
    /// is a fixed placeholder, so a real `redirect_uri` Client Identifier
    /// never matches it. The holders refuse these modes after client
    /// authentication anyway.
    #[rstest::rstest]
    #[case::dc_api("dc_api")]
    #[case::dc_api_jwt("dc_api.jwt")]
    fn dc_api_requests_bind_to_the_placeholder_return_uri(#[case] mode: &str) {
        let request = request(json!({
            "client_id": "redirect_uri:https://verifier.example/post",
            "response_type": "vp_token",
            "response_mode": mode,
            "nonce": "n",
            "dcql_query": { "credentials": [] }
        }));
        assert_eq!(request.return_uri().as_str(), "https://example.com/");
        assert!(verify_redirect_uri(&request, None).is_err());
    }

    /// `url::Url` normalises the host case, the default port and an empty
    /// path, so these spellings name the same response URI.
    #[rstest::rstest]
    #[case::host_case_and_default_port(
        "https://Verifier.example:443/post",
        "https://verifier.example/post"
    )]
    #[case::default_port_on_the_response_uri(
        "https://verifier.example/post",
        "https://verifier.example:443/post"
    )]
    #[case::empty_path("https://verifier.example", "https://verifier.example/")]
    #[case::same_query(
        "https://verifier.example/post?s=1",
        "https://verifier.example/post?s=1"
    )]
    #[case::same_fragment("https://verifier.example/post#f", "https://verifier.example/post#f")]
    fn equivalent_response_uris_are_accepted(#[case] client_uri: &str, #[case] response_uri: &str) {
        let request = direct_post(&format!("redirect_uri:{client_uri}"), response_uri);
        verify_redirect_uri(&request, None).expect("equivalent URIs must be accepted");
    }

    /// Anything else that changes the parsed URL is refused, including a
    /// fragment, although a fragment is never sent in the HTTP request.
    #[rstest::rstest]
    #[case::other_path("https://verifier.example/post", "https://verifier.example/other")]
    #[case::other_query(
        "https://verifier.example/post?s=1",
        "https://verifier.example/post?s=2"
    )]
    #[case::added_query("https://verifier.example/post", "https://verifier.example/post?s=1")]
    #[case::http_instead_of_https("https://verifier.example/post", "http://verifier.example/post")]
    #[case::other_port("https://verifier.example/post", "https://verifier.example:8443/post")]
    #[case::fragment_on_the_response_uri(
        "https://verifier.example/post",
        "https://verifier.example/post#f"
    )]
    #[case::fragment_on_the_client_id(
        "https://verifier.example/post#f",
        "https://verifier.example/post"
    )]
    #[case::other_fragment("https://verifier.example/post#f", "https://verifier.example/post#g")]
    fn different_response_uris_are_refused(#[case] client_uri: &str, #[case] response_uri: &str) {
        let request = direct_post(&format!("redirect_uri:{client_uri}"), response_uri);
        assert!(verify_redirect_uri(&request, None).is_err());
    }

    mod draft18 {
        use super::*;
        use openidvp_draft18::core::authorization_request::AuthorizationRequestObject as Draft18Request;

        fn direct_post(value: Json) -> Draft18Request {
            let mut request = json!({
                "response_type": "vp_token",
                "response_mode": "direct_post",
                "nonce": "n",
                "presentation_definition": { "id": "pd", "input_descriptors": [] }
            });
            request
                .as_object_mut()
                .unwrap()
                .extend(value.as_object().unwrap().clone());
            serde_json::from_value(request).expect("invalid draft-18 request")
        }

        fn redirect_uri_request(
            client_uri: &str,
            response_uri: &str,
            mode: &str,
        ) -> Draft18Request {
            direct_post(json!({
                "client_id": client_uri,
                "client_id_scheme": "redirect_uri",
                "response_uri": response_uri,
                "response_mode": mode
            }))
        }

        #[test]
        fn direct_post_jwt_requests_are_bound_to_the_response_uri() {
            let bound = redirect_uri_request(
                "https://verifier.example/post",
                "https://verifier.example/post",
                "direct_post.jwt",
            );
            assert_eq!(bound.return_uri().as_str(), "https://verifier.example/post");
            assert!(verify_redirect_uri_draft18(&bound, None).is_ok());

            let unbound = redirect_uri_request(
                "https://verifier.example/post",
                "https://attacker.example/post",
                "direct_post.jwt",
            );
            assert!(verify_redirect_uri_draft18(&unbound, None).is_err());
        }

        /// As in v1, the draft-18 crate refuses this while parsing.
        #[rstest::rstest]
        #[case::direct_post("direct_post")]
        #[case::direct_post_jwt("direct_post.jwt")]
        fn a_request_with_both_redirect_uri_and_response_uri_does_not_parse(#[case] mode: &str) {
            let err = serde_json::from_value::<Draft18Request>(json!({
                "client_id": "https://verifier.example/post",
                "client_id_scheme": "redirect_uri",
                "redirect_uri": "https://verifier.example/post",
                "response_uri": "https://attacker.example/post",
                "response_type": "vp_token",
                "response_mode": mode,
                "nonce": "n",
                "presentation_definition": { "id": "pd", "input_descriptors": [] }
            }))
            .expect_err("redirect_uri and response_uri together must not parse");
            assert!(err.to_string().contains("mutually exclusive"), "{err}");
        }

        #[rstest::rstest]
        #[case::host_case_and_default_port(
            "https://Verifier.example:443/post",
            "https://verifier.example/post"
        )]
        #[case::empty_path("https://verifier.example", "https://verifier.example/")]
        #[case::same_fragment("https://verifier.example/post#f", "https://verifier.example/post#f")]
        fn equivalent_response_uris_are_accepted(
            #[case] client_uri: &str,
            #[case] response_uri: &str,
        ) {
            let request = redirect_uri_request(client_uri, response_uri, "direct_post");
            verify_redirect_uri_draft18(&request, None).expect("equivalent URIs must be accepted");
        }

        #[rstest::rstest]
        #[case::other_path("https://verifier.example/post", "https://verifier.example/other")]
        #[case::other_query(
            "https://verifier.example/post?s=1",
            "https://verifier.example/post?s=2"
        )]
        #[case::http_instead_of_https(
            "https://verifier.example/post",
            "http://verifier.example/post"
        )]
        #[case::other_port("https://verifier.example/post", "https://verifier.example:8443/post")]
        #[case::fragment_on_the_response_uri(
            "https://verifier.example/post",
            "https://verifier.example/post#f"
        )]
        fn different_response_uris_are_refused(
            #[case] client_uri: &str,
            #[case] response_uri: &str,
        ) {
            let request = redirect_uri_request(client_uri, response_uri, "direct_post");
            assert!(verify_redirect_uri_draft18(&request, None).is_err());
        }

        #[test]
        fn redirect_uri_client_id_must_be_the_response_uri() {
            let bound = direct_post(json!({
                "client_id": "https://verifier.example/post",
                "client_id_scheme": "redirect_uri",
                "response_uri": "https://verifier.example/post"
            }));
            assert!(verify_redirect_uri_draft18(&bound, None).is_ok());

            let unbound = direct_post(json!({
                "client_id": "https://verifier.example/post",
                "client_id_scheme": "redirect_uri",
                "response_uri": "https://attacker.example/post"
            }));
            assert!(verify_redirect_uri_draft18(&unbound, None).is_err());

            let other_path = direct_post(json!({
                "client_id": "https://verifier.example/post",
                "client_id_scheme": "redirect_uri",
                "response_uri": "https://verifier.example/other"
            }));
            assert!(verify_redirect_uri_draft18(&other_path, None).is_err());
        }

        #[test]
        fn a_redirect_uri_prefix_on_the_client_id_is_removed() {
            let prefixed = direct_post(json!({
                "client_id": "redirect_uri:https://verifier.example/post",
                "client_id_scheme": "redirect_uri",
                "response_uri": "https://verifier.example/post"
            }));
            assert!(verify_redirect_uri_draft18(&prefixed, None).is_ok());

            let unbound = direct_post(json!({
                "client_id": "redirect_uri:https://verifier.example/post",
                "response_uri": "https://attacker.example/post"
            }));
            assert!(verify_redirect_uri_draft18(&unbound, None).is_err());
        }

        #[test]
        fn redirect_uri_request_objects_must_be_unsigned() {
            let bound = direct_post(json!({
                "client_id": "https://verifier.example/post",
                "client_id_scheme": "redirect_uri",
                "response_uri": "https://verifier.example/post"
            }));

            // Draft 18 does not require a `typ`.
            let unsigned = jwt(json!({ "alg": "none" }));
            assert!(verify_redirect_uri_draft18(&bound, Some(&unsigned)).is_ok());

            let signed = jwt(json!({ "alg": "ES256" }));
            assert!(verify_redirect_uri_draft18(&bound, Some(&signed)).is_err());

            let no_alg = jwt(json!({}));
            assert!(verify_redirect_uri_draft18(&bound, Some(&no_alg)).is_err());
        }
    }
}
