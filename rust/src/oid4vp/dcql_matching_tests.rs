use std::sync::Arc;

use serde_json::{json, Value as Json};

use crate::credential::{
    ietf_sd_jwt_vc::IetfSdJwtVc, json_vc::JsonVc, jwt_vc::JwtVc, ParsedCredential,
};
use crate::crypto::{KeyAlias, RustTestKeyManager};

use openid4vp::core::dcql_query::{DcqlCredentialQuery, DcqlQuery};

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
        JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into()).unwrap();
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
fn ldp_vc_claims_match_the_requested_values() {
    let alumni =
        JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into()).unwrap();
    let credential = ParsedCredential::new_ldp_vc(alumni);

    let requesting_type = |ty: &str| {
        query(
            "ldp_vc",
            json!({ "claims": [{ "path": ["type", null], "values": [ty] }] }),
        )
    };

    assert!(credential.satisfies_dcql_query(&requesting_type("AlumniCredential")));
    assert!(!credential.satisfies_dcql_query(&requesting_type("VehicleRegistrationCredential")));
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

fn requested_names(credential: &ParsedCredential, query: Json) -> Vec<String> {
    let dcql: DcqlQuery = serde_json::from_value(json!({ "credentials": [query] })).unwrap();
    credential
        .requested_fields_dcql(&dcql, "q")
        .iter()
        .map(|field| field.name.clone().unwrap())
        .collect()
}

#[test]
fn claim_sets_request_only_the_satisfied_option() {
    let alumni =
        JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into()).unwrap();
    let credential = ParsedCredential::new_ldp_vc(alumni);

    let query = |claim_sets: Json| {
        json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [
                { "id": "achievement", "path": ["credentialSubject", "achievement", "name"] },
                { "id": "alumni", "path": ["credentialSubject", "alumniOf", "name"] },
                { "id": "type", "path": ["type"] }
            ],
            "claim_sets": claim_sets
        })
    };

    assert_eq!(
        requested_names(&credential, query(json!([["achievement"], ["alumni"]]))),
        ["credentialSubject.alumniOf.name"]
    );
    assert_eq!(
        requested_names(&credential, query(json!([["type", "alumni"], ["alumni"]]))),
        ["type", "credentialSubject.alumniOf.name"]
    );
}

#[test]
fn claims_without_claim_sets_are_all_requested() {
    let alumni =
        JsonVc::new_from_json(include_str!("../../tests/examples/alumni_vc.json").into()).unwrap();
    let credential = ParsedCredential::new_ldp_vc(alumni);

    let query = json!({
        "id": "q",
        "format": "ldp_vc",
        "meta": {},
        "claims": [
            { "path": ["type"] },
            { "path": ["credentialSubject", "alumniOf", "name"] }
        ]
    });
    assert_eq!(
        requested_names(&credential, query),
        ["type", "credentialSubject.alumniOf.name"]
    );
}

#[test]
fn dc_sd_jwt_claims_match_the_requested_values() {
    let vc = IetfSdJwtVc::new_from_compact_sd_jwt(
        include_str!("../../tests/examples/dc+sd-jwt.jwt").into(),
    )
    .unwrap();
    let credential = ParsedCredential::new_dc_sd_jwt(vc);

    let requesting = |path: Json, values: Json| {
        query(
            "dc+sd-jwt",
            json!({ "claims": [{ "path": path, "values": values }] }),
        )
    };

    assert!(credential.satisfies_dcql_query(&requesting(
        json!(["affiliation_country"]),
        json!(["FR", "DE"])
    )));
    assert!(credential.satisfies_dcql_query(&requesting(json!(["issuing_country"]), json!(["DE"]))));
    assert!(!credential
        .satisfies_dcql_query(&requesting(json!(["affiliation_country"]), json!(["FR"]))));
    assert!(
        !credential.satisfies_dcql_query(&requesting(json!(["affiliation_country"]), json!([49])))
    );
}

#[test]
fn jwt_vc_claims_match_inside_the_vc_claim() {
    let vc =
        JwtVc::new_from_compact_jws(include_str!("../../tests/examples/mdl.jwt").into()).unwrap();
    let credential = ParsedCredential::new_jwt_vc_json(vc);

    let requesting = |claim: Json| query("jwt_vc_json", json!({ "claims": [claim] }));

    assert!(credential.satisfies_dcql_query(&requesting(json!({
        "path": ["credentialSubject", "driversLicense", "aamva_sex"],
        "values": [9]
    }))));
    assert!(credential.satisfies_dcql_query(&requesting(json!({
        "path": ["type", null],
        "values": ["Iso18013DriversLicenseCredential"]
    }))));

    assert!(!credential.satisfies_dcql_query(&requesting(json!({
        "path": ["credentialSubject", "driversLicense", "aamva_sex"],
        "values": [1]
    }))));
    assert!(!credential.satisfies_dcql_query(&requesting(json!({
        "path": ["vc", "credentialSubject", "driversLicense", "aamva_sex"]
    }))));
    assert!(!credential.satisfies_dcql_query(&requesting(json!({ "path": ["iss"] }))));
}

#[tokio::test]
async fn mdoc_claims_ignore_the_requested_values() {
    let key_manager = RustTestKeyManager::default();
    let alias = KeyAlias("claims_values".to_string());
    key_manager
        .generate_p256_signing_key(alias.clone())
        .await
        .unwrap();
    let mdoc = crate::mdl::util::generate_test_mdl(Arc::new(key_manager), alias).unwrap();
    let credential = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));

    let query = query(
        "mso_mdoc",
        json!({ "claims": [{
            "path": ["org.iso.18013.5.1", "given_name"],
            "values": ["not-the-given-name"]
        }] }),
    );
    assert!(credential.satisfies_dcql_query(&query));
}
