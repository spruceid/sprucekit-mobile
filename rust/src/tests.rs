use crate::{
    context::default_ld_json_context,
    credential::{json_vc::JsonVc, ParsedCredential},
    oid4vp::{holder::tests::KeySigner, ResponseOptions},
};

use oid4vci::oauth2::http::StatusCode;
use ssi::{
    jwk::{ECParams, Params},
    JWK,
};

const TMP_DIR: &str = "./target/tmp";
const OID4VP_URI: &str = "openid4vp://authorize?client_id=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz1AEkyzEHrWvfJX78zXZHiu6m%2Fopenid%2Fclient%2Fauthorization%2Fresponse&request_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz1AEkyzEHrWvfJX78zXZHiu6m%2Fopenid%2Fclient%2Fauthorization%2Frequest";

#[allow(dead_code)]
#[derive(Debug, thiserror::Error)]
#[error("HTTP error: {0}")]
pub struct TestError(StatusCode);

pub(crate) fn load_jwk() -> JWK {
    let key = p256::SecretKey::from_sec1_pem(include_str!("../tests/res/sec1.pem"))
        .expect("failed to instantiate key from pem");
    JWK::from(Params::EC(ECParams::from(&key)))
}

pub(crate) fn load_signer() -> KeySigner {
    KeySigner { jwk: load_jwk() }
}

// NOTE: This test is expected to be performed manually as it requires user interaction
// to parse the credential offer and oid4vp request url, set in the constant values
// above.
//
// Ensure oid4vci runs BEFORE oid4vp. This will ensure the test credentials are available.
#[ignore]
#[tokio::test]
pub async fn test_vc_playground_oid4vp() {
    let signer = load_signer();

    let path = format!("{TMP_DIR}/vc_test_credential_0.json");
    let contents = tokio::fs::read_to_string(path)
        .await
        .expect("failed to read test credential");

    let credential = ParsedCredential::new_ldp_vc(
        JsonVc::new_from_json(contents).expect("Failed to parse Json VC"),
    );

    let trusted_dids = vec![];

    let holder = crate::oid4vp::Holder::new_with_credentials(
        vec![credential.clone()],
        trusted_dids,
        Box::new(signer),
        Some(default_ld_json_context()),
        None,
    )
    .await
    .expect("Failed to create holder");

    let permission_request = holder
        .authorization_request(crate::oid4vp::AuthRequest::Url(OID4VP_URI.parse().unwrap()))
        .await
        .expect("Authorization request failed");

    let parsed_credentials = permission_request.credentials();

    assert_eq!(parsed_credentials.len(), 1);

    for credential in parsed_credentials.iter() {
        let requested_fields = permission_request.requested_fields(credential);
        assert!(!requested_fields.is_empty());
    }

    // NOTE: passing `parsed_credentials` as `selected_credentials`.
    // Get the first credential query ID from the DCQL query
    let credential_query_id = permission_request
        .dcql_query()
        .credentials()
        .first()
        .map(|c: &openid4vp::core::dcql_query::DcqlCredentialQuery| c.id().to_string())
        .unwrap_or_default();

    let response = permission_request
        .create_permission_response(
            parsed_credentials,
            vec![credential
                .requested_fields_dcql(permission_request.dcql_query(), &credential_query_id)
                .iter()
                .map(|rf| rf.path())
                .collect()],
            ResponseOptions::default(),
        )
        .await
        .expect("Failed to create permission response");

    holder
        .submit_permission_response(response)
        .await
        .expect("Permission response submission failed");
}
