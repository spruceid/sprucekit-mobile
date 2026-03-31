use crate::{
    context::default_ld_json_context,
    credential::{json_vc::JsonVc, ParsedCredential},
    oid4vp::{holder::tests::KeySigner, Oid4vpFacadeError, Oid4vpHolder, Oid4vpPresentationSigner, Oid4vpResponseOptions},
};

use oid4vci::oauth2::http::StatusCode;
use ssi::{
    claims::data_integrity::CryptosuiteString,
    crypto::Algorithm,
    jwk::{ECParams, Params},
    claims::jws::JwsSigner,
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

#[derive(Debug)]
struct TestKeySigner(KeySigner);

#[async_trait::async_trait]
impl Oid4vpPresentationSigner for TestKeySigner {
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, Oid4vpFacadeError> {
        let sig = self
            .0
            .jwk
            .sign_bytes(&payload)
            .await
            .expect("failed to sign Jws Payload");

        p256::ecdsa::Signature::from_slice(&sig)
            .map(|sig| sig.to_der().as_bytes().to_vec())
            .map_err(|e| Oid4vpFacadeError::RequestParsing(format!("{e:?}")))
    }

    fn algorithm(&self) -> Algorithm {
        self.0
            .jwk
            .algorithm
            .map(Algorithm::from)
            .unwrap_or(Algorithm::ES256)
    }

    async fn verification_method(&self) -> String {
        let jwk = self.jwk();
        crate::did::DidMethod::Key
            .vm_from_jwk(&jwk)
            .await
            .unwrap()
            .id
            .to_string()
    }

    fn did(&self) -> String {
        let jwk = self.jwk();
        crate::did::DidMethod::Key
            .did_from_jwk(&jwk)
            .unwrap()
            .to_string()
    }

    fn cryptosuite(&self) -> CryptosuiteString {
        CryptosuiteString::new("ecdsa-rdfc-2019".to_string()).unwrap()
    }

    fn jwk(&self) -> String {
        serde_json::to_string(&self.0.jwk.to_public()).unwrap()
    }
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

    let holder = Oid4vpHolder::new_with_credentials(
        vec![credential.clone()],
        trusted_dids,
        Box::new(TestKeySigner(signer)),
        Some(default_ld_json_context()),
        None,
    )
    .await
    .expect("Failed to create holder");

    let permission_request = holder
        .start(OID4VP_URI.to_string())
        .await
        .expect("Authorization request failed");

    let parsed_credentials = permission_request.credentials();

    assert_eq!(parsed_credentials.len(), 1);

    for credential in parsed_credentials.iter() {
        let requested_fields = permission_request.requested_fields(credential).unwrap();
        assert!(!requested_fields.is_empty());
    }

    let response = permission_request
        .create_permission_response(
            parsed_credentials.clone(),
            parsed_credentials
                .iter()
                .map(|credential| {
                    permission_request
                        .requested_fields(credential)
                        .unwrap()
                        .iter()
                        .map(|rf| rf.path.clone())
                        .collect()
                })
                .collect(),
            Oid4vpResponseOptions::default(),
        )
        .await
        .expect("Failed to create permission response");

    permission_request
        .submit_permission_response(response)
        .await
        .expect("Permission response submission failed");
}

#[cfg(test)]
mod tests {
    use oid4vci::{
        profile::{StandardFormat, W3cVcFormat},
        Oid4vciCredential,
    };

    use crate::credential::{CredentialFormat, RawCredential};

    #[test]
    fn jwt_vc_json_payload_not_double_encoded() {
        let jwt = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.\
                   eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tIn0.\
                   signature";

        let credential = Oid4vciCredential::new(serde_json::Value::String(jwt.to_owned()));
        let format = StandardFormat::W3c(W3cVcFormat::JwtVcJson);

        let raw = RawCredential::from_oid4vci(&format, credential).unwrap();

        assert_eq!(raw.format, CredentialFormat::JwtVcJson);
        assert_eq!(
            raw.payload,
            jwt.as_bytes(),
            "payload should be the raw JWT bytes, not JSON-encoded with quotes"
        );
    }

    #[test]
    fn jwt_vc_json_ld_payload_not_double_encoded() {
        let jwt = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.\
                   eyJpc3MiOiJodHRwczovL2V4YW1wbGUuY29tIn0.\
                   signature";

        let credential = Oid4vciCredential::new(serde_json::Value::String(jwt.to_owned()));
        let format = StandardFormat::W3c(W3cVcFormat::JwtVcJsonLd);

        let raw = RawCredential::from_oid4vci(&format, credential).unwrap();

        assert_eq!(raw.format, CredentialFormat::JwtVcJsonLd);
        assert_eq!(
            raw.payload,
            jwt.as_bytes(),
            "payload should be the raw JWT bytes, not JSON-encoded with quotes"
        );
    }

    #[test]
    fn ldp_vc_payload_is_json_object() {
        let vc = serde_json::json!({
            "@context": ["https://www.w3.org/2018/credentials/v1"],
            "type": ["VerifiableCredential"],
            "issuer": "https://example.com",
            "credentialSubject": {}
        });

        let credential = Oid4vciCredential::new(vc.clone());
        let format = StandardFormat::W3c(W3cVcFormat::LdpVc);

        let raw = RawCredential::from_oid4vci(&format, credential).unwrap();

        assert_eq!(raw.format, CredentialFormat::LdpVc);
        let roundtripped: serde_json::Value = serde_json::from_slice(&raw.payload).unwrap();
        assert_eq!(roundtripped, vc);
    }
}
