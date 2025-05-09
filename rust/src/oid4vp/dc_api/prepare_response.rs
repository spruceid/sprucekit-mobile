use anyhow::{Context, Result};
use base64::prelude::*;
use isomdl::{
    cbor,
    definitions::{helpers::ByteStr, DeviceResponse},
};
use serde::{Deserialize, Serialize};
use serde_json::Value as Json;
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Handover(String, ByteStr);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandoverInfo(String, String, String);

impl Handover {
    pub fn new(origin: String, client_id: String, nonce: String) -> Result<Self> {
        let handover_info = HandoverInfo(origin, client_id, nonce);
        let handover_info_bytes = cbor::to_vec(&handover_info)?;
        let handover_info_hash = ByteStr::from(Sha256::digest(handover_info_bytes).to_vec());
        Ok(Handover(
            "OpenID4VPDCAPIHandover".to_string(),
            handover_info_hash,
        ))
    }
}

pub fn vp_token(request_id: String, device_response: DeviceResponse) -> Result<Json> {
    let device_response_b64 = BASE64_URL_SAFE_NO_PAD.encode(
        cbor::to_vec(&device_response).context("failed to encode device response as CBOR")?,
    );
    let vp_token = Json::Object(
        [(request_id, Json::String(device_response_b64))]
            .into_iter()
            .collect(),
    );
    Ok(vp_token)
}
