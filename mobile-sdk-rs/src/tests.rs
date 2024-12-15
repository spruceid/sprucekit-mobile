use crate::{
    credential::{json_vc::JsonVc, ParsedCredential},
    oid4vci::AsyncHttpClient,
    oid4vp::{holder::tests::KeySigner, presentation::PresentationSigner},
    proof_of_possession::{generate_pop_complete, generate_pop_prepare},
};

use std::{collections::HashMap, str::FromStr, sync::Arc};

use oid4vci::oauth2::http::StatusCode;
use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use ssi::{
    jwk::{ECParams, Params},
    JWK,
};
use uniffi::deps::anyhow::Result;

use crate::oid4vci::{Oid4vci, Oid4vciExchangeOptions};

const TMP_DIR: &str = "./target/tmp";
const OID4VCI_CREDENTIAL_OFFER_URI: &str = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz1A68iKqcX2HbQGQfVSfFnjkM%2Fexchanges%2Fz1ACMqWTpAkbmkqrVUjsBcaDg%2Fopenid%2Fcredential-offer";
const OID4VP_URI: &str = "openid4vp://authorize?client_id=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz1A65c7e92MLSWXuv8NSDcFLg%2Fopenid%2Fclient%2Fauthorization%2Fresponse&request_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz19vRLNoFaBKDeDaMzRjUj8hi%2Fexchanges%2Fz1A65c7e92MLSWXuv8NSDcFLg%2Fopenid%2Fclient%2Fauthorization%2Frequest";

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

        let url = url::Url::from_str(&request.url).map_err(|e| {
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
    session.set_context_map(vc_playground_context())?;

    let credentials = session
        .exchange_credential(vec![pop], Oid4vciExchangeOptions::default())
        .await?;

    println!("Credentials: {credentials:?}");

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

// NOTE: This test is expected to be performed manually as it requires user interaction
// to parse the credential offer and oid4vp request url, set in the constant values
// above.
//
// Ensure oid4vci runs BEFORE oid4vp. This will ensure the test credentials are available.
#[ignore]
#[tokio::test]
pub async fn test_vc_playground_oid4vp() -> Result<()> {
    let signer = load_signer();

    let path = format!("{TMP_DIR}/vc_test_credential_0.json");
    let contents = tokio::fs::read_to_string(path)
        .await
        .expect("failed to read test credential");

    let credential = ParsedCredential::new_ldp_vc(JsonVc::new_from_json(contents)?);

    let trusted_dids = vec![];

    let context_map = vc_playground_context();

    let holder = crate::oid4vp::Holder::new_with_credentials(
        vec![credential],
        trusted_dids,
        Box::new(signer),
        Some(context_map),
    )
    .await?;

    let permission_request = holder
        .authorization_request(OID4VP_URI.parse().unwrap())
        .await?;

    let parsed_credentials = permission_request.credentials();

    assert_eq!(parsed_credentials.len(), 1);

    for credential in parsed_credentials.iter() {
        let requested_fields = permission_request.requested_fields(&credential);

        assert!(requested_fields.len() > 0);
    }

    // NOTE: passing `parsed_credentials` as `selected_credentials`.
    let response = permission_request
        .create_permission_response(parsed_credentials)
        .await?;

    holder.submit_permission_response(response).await?;

    Ok(())
}

pub(crate) fn vc_playground_context() -> HashMap<String, String> {
    let mut context: HashMap<String, String> = HashMap::new();

    context.insert(
        "https://w3id.org/first-responder/v1".into(),
        include_str!("../tests/context/w3id_org_first_responder_v1.json").into(),
    );
    context.insert(
        "https://w3id.org/vdl/aamva/v1".into(),
        include_str!("../tests/context/w3id_org_vdl_aamva_v1.json").into(),
    );
    context.insert(
        "https://w3id.org/citizenship/v3".into(),
        include_str!("../tests/context/w3id_org_citizenship_v3.json").into(),
    );
    context.insert(
        "https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.2.json".into(),
        include_str!("../tests/context/purl_imsglobal_org_spec_ob_v3p0_context_3_0_2.json").into(),
    );
    context.insert(
        "https://w3id.org/citizenship/v4rc1".into(),
        include_str!("../tests/context/w3id_org_citizenship_v4rc1.json").into(),
    );
    context.insert(
        "https://w3id.org/vc/render-method/v2rc1".into(),
        include_str!("../tests/context/w3id_org_vc_render_method_v2rc1.json").into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/alumni/v2.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_alumni_v2.json").into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/first-responder/v1.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_first_responder_v1.json")
            .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/shim-render-method-term/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_shim_render_method_term_v1.json"
        )
        .into(),
    );
    context.insert("https://examples.vcplayground.org/contexts/shim-VCv1.1-common-example-terms/v1.json".into(), include_str!("../tests/context/examples_vcplayground_org_contexts_shim_vcv1_1_common_example_terms_v1.json").into());
    context.insert(
        "https://examples.vcplayground.org/contexts/utopia-natcert/v1.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_utopia_natcert_v1.json")
            .into(),
    );
    context.insert(
        "https://www.w3.org/ns/controller/v1".into(),
        include_str!("../tests/context/w3_org_ns_controller_v1.json").into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/movie-ticket/v2.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_movie_ticket_v2.json")
            .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/food-safety-certification/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_food_safety_certification_v1.json"
        )
        .into(),
    );
    context.insert("https://examples.vcplayground.org/contexts/academic-course-credential/v1.json".into(), include_str!("../tests/context/examples_vcplayground_org_contexts_academic_course_credential_v1.json").into());
    context.insert(
        "https://examples.vcplayground.org/contexts/gs1-8110-coupon/v2.json".into(),
        include_str!("../tests/context/examples_vcplayground_org_contexts_gs1_8110_coupon_v2.json")
            .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/customer-loyalty/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_customer_loyalty_v1.json"
        )
        .into(),
    );
    context.insert(
        "https://examples.vcplayground.org/contexts/movie-ticket-vcdm-v2/v1.json".into(),
        include_str!(
            "../tests/context/examples_vcplayground_org_contexts_movie_ticket_vcdm_v2_v1.json"
        )
        .into(),
    );

    context
}
