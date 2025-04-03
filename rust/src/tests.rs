use crate::{
    context::default_ld_json_context,
    credential::{json_vc::JsonVc, ParsedCredential},
    oid4vci::AsyncHttpClient,
    oid4vp::{holder::tests::KeySigner, presentation::PresentationSigner, ResponseOptions},
    proof_of_possession::{generate_pop_complete, generate_pop_prepare},
};

use std::{str::FromStr, sync::Arc};

use oid4vci::oauth2::http::StatusCode;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use ssi::{
    jwk::{ECParams, Params},
    JWK,
};
use uniffi::deps::anyhow::Result;
use url::Url;

use crate::oid4vci::{Oid4vci, Oid4vciExchangeOptions};

const TMP_DIR: &str = "./target/tmp";
const OID4VCI_CREDENTIAL_OFFER_URI: &str = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz1A68iKqcX2HbQGQfVSfFnjkM%2Fexchanges%2Fz19m8ENjZNgFj48816cbC2o2u%2Fopenid%2Fcredential-offer";
const MSO_MDOC_OID4VCI_CREDENTIAL_OFFER_URI: &str = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22https%3A%2F%2F0b16-2001-1284-f50e-a746-f8de-7ed-6278-9f7e.ngrok-free.app%22%2C%22credential_configuration_ids%22%3A%5B%22org.iso.18013.5.1.mDL%22%5D%2C%22grants%22%3A%7B%22urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Apre-authorized_code%22%3A%7B%22pre-authorized_code%22%3A%22JIHGFEDCBA%22%2C%22tx_code%22%3Anull%2C%22interval%22%3Anull%2C%22authorization_server%22%3Anull%7D%7D%7D";
const OID4VP_URI: &str = "openid4vp://authorize?client_id=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz1AEkyzEHrWvfJX78zXZHiu6m%2Fopenid%2Fclient%2Fauthorization%2Fresponse&request_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz1AEkyzEHrWvfJX78zXZHiu6m%2Fopenid%2Fclient%2Fauthorization%2Frequest";

#[derive(Debug, thiserror::Error)]
#[error("HTTP error: {0}")]
pub struct TestError(StatusCode);

// NOTE: This could be the basis for the default async client for oid4vci,
// but it's currently only used for testing purposes.
//
// TODO: consider moving this into the async.
pub struct TestAsyncHttpClient(pub reqwest::Client);

impl TestAsyncHttpClient {
    pub fn new() -> Self {
        Self(reqwest::Client::new())
    }
}

#[async_trait::async_trait]
impl AsyncHttpClient for TestAsyncHttpClient {
    async fn http_client(
        &self,
        request: crate::oid4vci::HttpRequest,
    ) -> std::result::Result<crate::oid4vci::HttpResponse, crate::oid4vci::HttpClientError> {
        let mut headers = HeaderMap::new();

        for (name, value) in request.headers.clone().into_iter() {
            headers.insert(
                HeaderName::from_str(name.as_str()).unwrap(),
                HeaderValue::from_bytes(value.as_bytes()).unwrap(),
            );
        }

        let method = reqwest::Method::from_str(&request.method).map_err(|e| {
            println!("Test AsyncHttpClient Error: {e:?}");
            crate::oid4vci::HttpClientError::MethodParse
        })?;

        let url = Url::from_str(&request.url).map_err(|e| {
            println!("Test AsyncHttpClient Error: {e:?}");
            crate::oid4vci::HttpClientError::UrlParse
        })?;

        let req =
            reqwest::RequestBuilder::from_parts(self.0.clone(), reqwest::Request::new(method, url))
                .body(request.body)
                .headers(headers);

        let response = self.0.execute(req.build().unwrap()).await.map_err(|e| {
            println!("Test AsyncHttpClient Error: {e:?}");
            crate::oid4vci::HttpClientError::RequestBuilder
        })?;

        let status_code = response.status().as_u16();

        let headers = response
            .headers()
            .into_iter()
            .map(|(k, v)| (k.to_string(), v.to_str().unwrap().to_string()))
            .collect();

        let body = response.bytes().await.map_err(|e| {
            println!("Test AsyncHttpClient Error: {e:?}");
            crate::oid4vci::HttpClientError::ResponseBuilder
        })?;

        Ok(crate::oid4vci::HttpResponse {
            status_code,
            headers,
            body: body.to_vec(),
        })
    }
}

pub(crate) fn load_jwk() -> JWK {
    let key = p256::SecretKey::from_sec1_pem(include_str!("../tests/res/sec1.pem"))
        .expect("failed to instantiate key from pem");
    JWK::from(Params::EC(ECParams::from(&key)))
}

pub(crate) fn load_signer() -> KeySigner {
    KeySigner { jwk: load_jwk() }
}

#[ignore]
#[tokio::test]
pub async fn test_vc_playground_oid4vci() -> Result<()> {
    let signer = load_signer();

    // Create a new wallet;
    // Load the credential via oid4vci;
    // Present the credential via oid4vp;
    let client = Arc::new(TestAsyncHttpClient::new());
    let session = Oid4vci::with_async_client(client);

    let credential_offer = OID4VCI_CREDENTIAL_OFFER_URI.into();
    let client_id = "skit-demo-wallet".into();
    let redirect_url = "https://spruceid.com".into();

    session
        .initiate_with_offer(credential_offer, client_id, redirect_url)
        .await?;

    let nonce = session.exchange_token().await?;
    let metadata = session.get_metadata()?;
    let audience = metadata.issuer();
    let did_method = crate::did::DidMethod::Key;
    let public_jwk = signer.jwk();
    let duration_in_secs = None;

    let pop_prepare =
        generate_pop_prepare(audience, nonce, did_method, public_jwk, duration_in_secs).await?;

    let signature = signer.sign_jwt(pop_prepare.clone()).await?;

    let pop = generate_pop_complete(pop_prepare, signature)?;

    // Load VC Playground Context
    session.set_context_map(default_ld_json_context())?;

    let credentials = session
        .exchange_credential(vec![pop], Oid4vciExchangeOptions::default())
        .await?;

    for (index, crate::oid4vci::CredentialResponse { payload, .. }) in
        credentials.iter().enumerate()
    {
        let path = format!("{TMP_DIR}/vc_test_credential_{index}.json");

        println!("Saving credential to path: {path}");

        // Save this payload into a .ldp_vc file.
        tokio::fs::write(path, payload).await?;
    }

    Ok(())
}

#[ignore]
#[tokio::test]
pub async fn test_mso_mdoc_oid4vci() -> Result<()> {
    let signer = load_signer();

    // Create a new wallet;
    // Load the credential via oid4vci;
    // Present the credential via oid4vp;
    let client = Arc::new(TestAsyncHttpClient::new());
    let session = Oid4vci::with_async_client(client);

    let credential_offer = MSO_MDOC_OID4VCI_CREDENTIAL_OFFER_URI.into();
    let client_id = "skit-demo-wallet".into();
    let redirect_url = "https://spruceid.com".into();

    session
        .initiate_with_offer(credential_offer, client_id, redirect_url)
        .await?;

    let nonce = session.exchange_token().await?;
    let metadata = session.get_metadata()?;
    let audience = metadata.issuer();
    let did_method = crate::did::DidMethod::Key;
    let public_jwk = signer.jwk();
    let duration_in_secs = None;

    let pop_prepare =
        generate_pop_prepare(audience, nonce, did_method, public_jwk, duration_in_secs).await?;

    let signature = signer.sign_jwt(pop_prepare.clone()).await?;

    let pop = generate_pop_complete(pop_prepare, signature)?;

    // Load VC Playground Context
    session.set_context_map(default_ld_json_context())?;

    let credentials = session
        .exchange_credential(
            vec![pop],
            Oid4vciExchangeOptions {
                verify_after_exchange: Some(false),
            },
        )
        .await?;

    // Create a test key alias for the mdocs
    let key_alias = crate::crypto::KeyAlias("test_key".to_string());

    for (index, crate::oid4vci::CredentialResponse { payload, format }) in
        credentials.iter().enumerate()
    {
        let path = format!("{TMP_DIR}/vc_test_credential_{index}.json");

        // Save this payload into a .ldp_vc file.
        tokio::fs::write(path, payload).await?;

        // If this is an mdoc credential, create an Mdoc object
        if *format == crate::credential::CredentialFormat::MsoMdoc {
            let utf8_payload = String::from_utf8_lossy(payload).to_string();

            // Create the Mdoc using the new method that handles server payloads
            let mdoc = crate::credential::mdoc::Mdoc::new_from_base64url_encoded_issuer_signed(
                utf8_payload,
                key_alias.clone(),
            )?;

            // Verify the mdoc was created correctly
            let _ = mdoc.details();
        }
    }

    Ok(())
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
    let response = permission_request
        .create_permission_response(
            parsed_credentials,
            vec![credential
                .requested_fields(&permission_request.definition)
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
