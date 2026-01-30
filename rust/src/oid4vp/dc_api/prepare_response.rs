use anyhow::{Context, Result};
use base64::prelude::*;
use isomdl::{cbor, definitions::DeviceResponse};
use serde_json::Value as Json;

/// Build a vp_token for DCQL response.
///
/// vp_token is a JSON object where:
/// - keys are the credential query IDs from the DCQL query
/// - values are arrays of one or more Verifiable Presentations
///
/// See: https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-8.1
pub fn vp_token(request_id: String, device_response: DeviceResponse) -> Result<Json> {
    let device_response_b64 = BASE64_URL_SAFE_NO_PAD.encode(
        cbor::to_vec(&device_response).context("failed to encode device response as CBOR")?,
    );
    let vp_token = Json::Object(
        [(
            request_id,
            Json::Array(vec![Json::String(device_response_b64)]),
        )]
        .into_iter()
        .collect(),
    );
    Ok(vp_token)
}
