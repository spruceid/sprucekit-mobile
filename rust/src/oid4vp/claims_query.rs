//! DCQL claims matching (OID4VP 1.0 §6.4.1, §7): a credential matches if it
//! holds every requested claim, or every claim of one `claim_sets` option.

use openid4vp::core::dcql_query::{
    DcqlCredentialClaimsQuery, DcqlCredentialClaimsQueryPath as PathComponent, DcqlCredentialQuery,
};
use serde_json::Value as Json;

/// Why processing a claims path pointer selects no claim (§7.1.1, §7.2.1).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub(crate) enum ClaimsPathError {
    #[error("path component {0} selects a key of a non-object")]
    NotAnObject(usize),
    #[error("path component {0} selects from a non-array")]
    NotAnArray(usize),
    #[error("the path selects no claim")]
    NoClaim,
    #[error("an mdoc path must be exactly a namespace and a data element identifier")]
    InvalidMdocPath,
}

/// Process a claims path pointer against a JSON-based credential (§7.1.1),
/// and return the selected claims.
pub(crate) fn select_json<'a>(
    credential: &'a Json,
    path: &[PathComponent],
) -> Result<Vec<&'a Json>, ClaimsPathError> {
    let mut selected = vec![credential];

    for (i, component) in path.iter().enumerate() {
        selected = match component {
            // Objects lacking the key drop out.
            PathComponent::String(key) => selected
                .into_iter()
                .map(|element| {
                    element
                        .as_object()
                        .map(|object| object.get(key))
                        .ok_or(ClaimsPathError::NotAnObject(i))
                })
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten()
                .collect(),
            PathComponent::Null => selected
                .into_iter()
                .map(|element| element.as_array().ok_or(ClaimsPathError::NotAnArray(i)))
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten()
                .collect(),
            // Arrays too short for the index drop out.
            PathComponent::Integer(index) => selected
                .into_iter()
                .map(|element| {
                    element
                        .as_array()
                        .map(|array| array.get(*index))
                        .ok_or(ClaimsPathError::NotAnArray(i))
                })
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten()
                .collect(),
        };
    }

    if selected.is_empty() {
        return Err(ClaimsPathError::NoClaim);
    }

    Ok(selected)
}

/// Parse a claims path pointer into an ISO mdoc (§7.2.1) as its namespace
/// and data element identifier.
pub(crate) fn mdoc_path(path: &[PathComponent]) -> Result<(&str, &str), ClaimsPathError> {
    match path {
        [PathComponent::String(namespace), PathComponent::String(element)] => {
            Ok((namespace, element))
        }
        _ => Err(ClaimsPathError::InvalidMdocPath),
    }
}

/// Whether the credential, per `has_claim`, holds the requested claims
/// (§6.4.1). A `claim_sets` without `claims`, or naming an unknown claim
/// `id`, cannot be satisfied.
pub(crate) fn satisfies_claims(
    credential_query: &DcqlCredentialQuery,
    has_claim: impl Fn(&DcqlCredentialClaimsQuery) -> bool,
) -> bool {
    let Some(claims) = credential_query.claims() else {
        if credential_query.claim_sets().is_some() {
            log::warn!(
                "credential query {:?} has `claim_sets` but no `claims`",
                credential_query.id()
            );
            return false;
        }
        return true;
    };

    let Some(claim_sets) = credential_query.claim_sets() else {
        return claims.iter().all(has_claim);
    };

    claim_sets.iter().any(|option| {
        option.iter().all(|id| {
            claims
                .iter()
                .find(|claim| claim.id() == Some(id))
                .is_some_and(&has_claim)
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn path(value: Json) -> Vec<PathComponent> {
        serde_json::from_value(value).unwrap()
    }

    /// The credential from the §7.3 example.
    fn arthur() -> Json {
        json!({
            "name": "Arthur Dent",
            "address": {
                "street_address": "42 Market Street",
                "locality": "Milliways",
                "postal_code": "12345"
            },
            "degrees": [
                { "type": "Bachelor of Science", "university": "University of Betelgeuse" },
                { "type": "Master of Science", "university": "University of Betelgeuse" }
            ],
            "nationalities": ["British", "Betelgeusian"]
        })
    }

    #[test]
    fn selects_the_spec_examples() {
        let credential = arthur();
        let select = |p: Json| select_json(&credential, &path(p)).unwrap();

        assert_eq!(select(json!(["name"])), [&json!("Arthur Dent")]);
        assert_eq!(select(json!(["address"])), [&credential["address"]]);
        assert_eq!(
            select(json!(["address", "street_address"])),
            [&json!("42 Market Street")]
        );
        assert_eq!(
            select(json!(["degrees", null, "type"])),
            [&json!("Bachelor of Science"), &json!("Master of Science")]
        );
        assert_eq!(
            select(json!(["nationalities", 1])),
            [&json!("Betelgeusian")]
        );
    }

    #[test]
    fn missing_claims_select_nothing() {
        let credential = arthur();
        for missing in [
            json!(["birthdate"]),
            json!(["address", "country"]),
            json!(["nationalities", 2]),
            json!(["degrees", null, "grade"]),
        ] {
            assert_eq!(
                select_json(&credential, &path(missing.clone())),
                Err(ClaimsPathError::NoClaim),
                "{missing}"
            );
        }
    }

    #[test]
    fn drops_only_the_elements_lacking_the_key() {
        let credential = json!({ "degrees": [{ "type": "BSc" }, { "university": "Betelgeuse" }] });
        assert_eq!(
            select_json(&credential, &path(json!(["degrees", null, "type"]))),
            Ok(vec![&json!("BSc")])
        );
    }

    #[test]
    fn type_mismatches_are_errors() {
        let credential = arthur();
        let select = |p: Json| select_json(&credential, &path(p));

        assert_eq!(
            select(json!(["name", "first"])),
            Err(ClaimsPathError::NotAnObject(1))
        );
        assert_eq!(
            select(json!(["address", null])),
            Err(ClaimsPathError::NotAnArray(1))
        );
        assert_eq!(
            select(json!(["address", 0])),
            Err(ClaimsPathError::NotAnArray(1))
        );
        assert_eq!(
            select(json!(["degrees", "type"])),
            Err(ClaimsPathError::NotAnObject(1))
        );
    }

    #[test]
    fn mdoc_paths_are_a_namespace_and_an_element() {
        assert_eq!(
            mdoc_path(&path(json!(["org.iso.18013.5.1", "given_name"]))),
            Ok(("org.iso.18013.5.1", "given_name"))
        );
        for invalid in [
            json!(["org.iso.18013.5.1"]),
            json!(["org.iso.18013.5.1", "given_name", "x"]),
            json!(["org.iso.18013.5.1", 0]),
            json!([null, "given_name"]),
        ] {
            assert_eq!(
                mdoc_path(&path(invalid.clone())),
                Err(ClaimsPathError::InvalidMdocPath),
                "{invalid}"
            );
        }
    }

    fn query(claims: Json) -> DcqlCredentialQuery {
        let mut query = json!({ "id": "q", "format": "ldp_vc", "meta": {} });
        query
            .as_object_mut()
            .unwrap()
            .extend(claims.as_object().unwrap().clone());
        serde_json::from_value(query).unwrap()
    }

    /// Whether `arthur()` satisfies the query.
    fn arthur_satisfies(query: &DcqlCredentialQuery) -> bool {
        let credential = arthur();
        satisfies_claims(query, |claim| {
            select_json(&credential, claim.path()).is_ok()
        })
    }

    #[test]
    fn no_claims_requests_nothing() {
        assert!(arthur_satisfies(&query(json!({}))));
    }

    #[test]
    fn every_claim_is_required_without_claim_sets() {
        assert!(arthur_satisfies(&query(json!({
            "claims": [{ "path": ["name"] }, { "path": ["address", "locality"] }]
        }))));
        assert!(!arthur_satisfies(&query(json!({
            "claims": [{ "path": ["name"] }, { "path": ["birthdate"] }]
        }))));
    }

    #[test]
    fn one_claim_set_option_is_enough() {
        let claims = json!([
            { "id": "a", "path": ["name"] },
            { "id": "b", "path": ["birthdate"] },
            { "id": "c", "path": ["address", "postal_code"] }
        ]);

        // The first option lacks `birthdate`, the second is complete.
        assert!(arthur_satisfies(&query(json!({
            "claims": claims,
            "claim_sets": [["a", "b"], ["a", "c"]]
        }))));
        // No option is complete.
        assert!(!arthur_satisfies(&query(json!({
            "claims": claims,
            "claim_sets": [["a", "b"], ["b", "c"]]
        }))));
    }

    #[test]
    fn claim_sets_naming_an_unknown_claim_cannot_be_satisfied() {
        assert!(!arthur_satisfies(&query(json!({
            "claims": [{ "id": "a", "path": ["name"] }],
            "claim_sets": [["a", "z"]]
        }))));
    }

    #[test]
    fn claim_sets_without_claims_cannot_be_satisfied() {
        assert!(!arthur_satisfies(&query(json!({ "claim_sets": [["a"]] }))));
    }
}

/// Claims matching per format, through `ParsedCredential::satisfies_dcql_query`.
#[cfg(test)]
mod credential_tests {
    use std::sync::Arc;

    use serde_json::{json, Value as Json};

    use crate::credential::{ietf_sd_jwt_vc::IetfSdJwtVc, json_vc::JsonVc, ParsedCredential};
    use crate::crypto::{KeyAlias, RustTestKeyManager};

    use super::*;

    fn query(format: &str, claims: Json) -> DcqlCredentialQuery {
        let mut query = json!({ "id": "q", "format": format, "meta": {} });
        query
            .as_object_mut()
            .unwrap()
            .extend(claims.as_object().unwrap().clone());
        serde_json::from_value(query).unwrap()
    }

    fn claims(paths: Json) -> Json {
        let claims: Vec<Json> = paths
            .as_array()
            .unwrap()
            .iter()
            .map(|path| json!({ "path": path }))
            .collect();
        json!({ "claims": claims })
    }

    #[test]
    fn ldp_vc_claims_match_from_the_credential_root() {
        let alumni =
            JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into())
                .unwrap();
        let credential = ParsedCredential::new_ldp_vc(alumni);

        let present = claims(json!([["credentialSubject", "alumniOf", "name"]]));
        assert!(credential.satisfies_dcql_query(&query("ldp_vc", present)));

        let missing = claims(json!([["credentialSubject", "achievement", "name"]]));
        assert!(!credential.satisfies_dcql_query(&query("ldp_vc", missing)));

        // The first option lacks `achievement`, the second is complete.
        let claim_sets = json!({
            "claims": [
                { "id": "achievement", "path": ["credentialSubject", "achievement", "name"] },
                { "id": "alumni", "path": ["credentialSubject", "alumniOf", "name"] }
            ],
            "claim_sets": [["achievement"], ["alumni"]]
        });
        assert!(credential.satisfies_dcql_query(&query("ldp_vc", claim_sets)));
    }

    #[test]
    fn dc_sd_jwt_claims_match_the_disclosed_claims() {
        let vc = IetfSdJwtVc::new_from_compact_sd_jwt(
            include_str!("../../tests/examples/dc+sd-jwt.jwt").into(),
        )
        .unwrap();
        let credential = ParsedCredential::new_dc_sd_jwt(vc);

        // `health_insurance_id` is selectively disclosable.
        let present = claims(json!([["health_insurance_id"]]));
        assert!(credential.satisfies_dcql_query(&query("dc+sd-jwt", present)));

        let missing = claims(json!([["health_insurance_id"], ["birthdate"]]));
        assert!(!credential.satisfies_dcql_query(&query("dc+sd-jwt", missing)));
    }

    #[tokio::test]
    async fn mdoc_claims_match_a_namespace_and_element() {
        let key_manager = RustTestKeyManager::default();
        let alias = KeyAlias("claims_query".to_string());
        key_manager
            .generate_p256_signing_key(alias.clone())
            .await
            .unwrap();
        let mdoc = crate::mdl::util::generate_test_mdl(Arc::new(key_manager), alias).unwrap();
        let credential = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));

        let present = claims(json!([["org.iso.18013.5.1", "given_name"]]));
        assert!(credential.satisfies_dcql_query(&query("mso_mdoc", present)));

        for missing in [
            json!([["org.iso.18013.5.1", "nickname"]]),
            json!([["org.iso.18013.5.1.aamva", "given_name"]]),
            // Not a namespace and a data element identifier (§7.2.1).
            json!([["org.iso.18013.5.1", "given_name", "x"]]),
        ] {
            let query = query("mso_mdoc", claims(missing.clone()));
            assert!(!credential.satisfies_dcql_query(&query), "{missing}");
        }
    }
}
