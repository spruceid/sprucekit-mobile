use anyhow::{bail, Context, Result};
use base64::prelude::*;
use isomdl::{cbor, definitions::DeviceResponse};
use openid4vp::core::{
    authorization_request::AuthorizationRequestObject,
    dcql_query::DcqlQuery,
    jwe::{find_encryption_jwk, JweBuilder, DEFAULT_ENC},
    object::ParsingErrorContext,
    response::{parameters::State, AuthorizationResponse, JwtAuthorizationResponse},
};
use serde_json::{json, Value as Json};

/// Build an encrypted authorization response for mdoc presentations.
///
/// Per OID4VP 1.0 §8.3.1, the response is encrypted using the verifier's public key
/// and sent via HTTP POST with the `response` parameter containing the JWE.
pub fn build_response(
    request: &AuthorizationRequestObject,
    dcql_query: &DcqlQuery,
    device_response: DeviceResponse,
) -> Result<AuthorizationResponse> {
    let device_response = BASE64_URL_SAFE_NO_PAD.encode(
        cbor::to_vec(&device_response).context("failed to encode device response as CBOR")?,
    );

    let credential_query_id = dcql_query
        .credentials()
        .first()
        .map(|c| c.id().to_string())
        .unwrap_or_else(|| "mDL".to_string());

    let vp_token = json!({
        credential_query_id: [device_response]
    });

    let jwe = build_jwe(request, vp_token)?;

    let authorization_response =
        AuthorizationResponse::Jwt(JwtAuthorizationResponse { response: jwe });

    Ok(authorization_response)
}

/// Build a JWE-encrypted response per OID4VP 1.0 §8.3.
fn build_jwe(request: &AuthorizationRequestObject, vp_token: Json) -> Result<String> {
    let client_metadata = request
        .client_metadata()
        .context("failed to resolve client_metadata")?;

    // Per OID4VP v1.0 §8.3, alg comes from the JWK's `alg` field
    let jwks = client_metadata.jwks().parsing_error()?;
    let keys: Vec<_> = jwks.keys.iter().collect();
    let jwk_info = find_encryption_jwk(keys.into_iter())
        .context("no suitable encryption key found in client metadata")?;

    let alg = &jwk_info.alg;
    if alg != "ECDH-ES" {
        bail!("unsupported encryption alg: {alg}")
    }

    // Per OID4VP v1.0 §8.3, enc comes from encrypted_response_enc_values_supported (default: A128GCM)
    let enc = client_metadata
        .encrypted_response_enc_values_supported()
        .parsing_error()?
        .0
        .first()
        .cloned()
        .unwrap_or_else(|| DEFAULT_ENC.to_string());
    if enc != DEFAULT_ENC {
        bail!("unsupported encryption scheme: {enc}")
    }

    // Build the payload with vp_token and optional state
    let mut payload = json!({
        "vp_token": vp_token
    });

    if let Some(state) = get_state_from_request(request)? {
        payload["state"] = json!(state);
    }

    tracing::debug!(
        "JWE payload:\n{}",
        serde_json::to_string_pretty(&payload).unwrap()
    );

    let jwk_json: Json = serde_json::to_value(&jwk_info.jwk).context("failed to serialize JWK")?;

    let mut builder = JweBuilder::new()
        .payload(payload)
        .recipient_key_json(&jwk_json)
        .context("invalid recipient JWK")?
        .alg(alg)
        .enc(&enc);

    if let Some(kid) = &jwk_info.kid {
        builder = builder.kid(kid);
    }

    let jwe = builder.build().context("failed to build JWE")?;

    tracing::debug!("JWE: {jwe}");

    Ok(jwe)
}

pub fn get_state_from_request(request: &AuthorizationRequestObject) -> Result<Option<String>> {
    request
        .get::<State>()
        .map(|state| Ok(state.parsing_error()?.0))
        .transpose()
}
