use std::sync::Arc;

use base64::prelude::*;
use ciborium::Value as Cbor;
use hpke::{
    aead::AesGcm128, kdf::HkdfSha256, kem::DhP256HkdfSha256, Deserializable, OpModeS, Serializable,
};
use isomdl::{
    cbor,
    definitions::{
        device_request::DeviceRequest, helpers::ByteStr, session::SessionTranscript, CoseKey,
        EC2Curve, EC2Y,
    },
};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{
    credential::{ParsedCredential, ParsedCredentialInner},
    crypto::KeyStore,
    oid4vp::iso_18013_7::{self, requested_values::RequestMatch180137, ApprovedResponse180137},
};

use super::DcApiError;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DcApiRequest {
    device_request: String,
    encryption_info: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EncryptionParameters {
    nonce: ByteStr,
    recipient_public_key: CoseKey,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct EncryptionInfo(String, EncryptionParameters);

#[derive(Clone, Debug, Serialize, Deserialize)]
struct EncryptedResponse(String, EncryptedResponseData);

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EncryptedResponseData {
    enc: ByteStr,
    cipher_text: ByteStr,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionTranscriptDCAPI<H>(Cbor, Cbor, H);

impl<H: Serialize + DeserializeOwned> SessionTranscript for SessionTranscriptDCAPI<H> {}

impl<H> SessionTranscriptDCAPI<H> {
    fn new(handover: H) -> Self {
        Self(Cbor::Null, Cbor::Null, handover)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Handover(String, ByteStr);

#[derive(Debug, Clone, Serialize, Deserialize)]
struct HandoverInfo(String, String);

impl Handover {
    pub fn new(encryption_info_base64: String, origin: String) -> anyhow::Result<Self> {
        let handover_info = HandoverInfo(encryption_info_base64, origin);
        let handover_info_bytes = cbor::to_vec(&handover_info)?;
        let handover_info_hash = ByteStr::from(Sha256::digest(handover_info_bytes).to_vec());
        Ok(Handover("dcapi".to_string(), handover_info_hash))
    }
}

#[derive(Deserialize, Serialize)]
struct DcApiResponseData {
    response: String,
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn build_annex_c_response(
    request: Vec<u8>,
    origin: String,
    selected_match: Arc<RequestMatch180137>,
    parsed_credentials: Vec<Arc<ParsedCredential>>,
    approved_response: ApprovedResponse180137,
    key_store: Arc<dyn KeyStore>,
) -> Result<Vec<u8>, DcApiError> {
    let req: DcApiRequest = serde_json::from_slice(&request).map_err(|e| {
        DcApiError::InvalidRequest(format!("Could not deserialize DC API request: {e:?}"))
    })?;
    let device_request_bytes = BASE64_URL_SAFE_NO_PAD
        .decode(req.device_request)
        .map_err(|e| {
            DcApiError::InvalidRequest(format!("Could not decode base64 device request: {e:?}"))
        })?;
    // TODO Add trusted roots and implement chain verification (see WalletActivity)
    let _device_request: DeviceRequest = cbor::from_slice(&device_request_bytes).map_err(|e| {
        DcApiError::InvalidRequest(format!("Could not decode CBOR device request: {e:?}"))
    })?;
    let encryption_info_base64 = req.encryption_info;
    let encryption_info_bytes = BASE64_URL_SAFE_NO_PAD
        .decode(encryption_info_base64.clone())
        .map_err(|e| {
            DcApiError::InvalidRequest(format!("Could not decode base64 device request: {e:?}"))
        })?;
    let encryption_info: EncryptionInfo =
        cbor::from_slice(&encryption_info_bytes).map_err(|e| {
            DcApiError::InvalidRequest(format!("Could not decode CBOR device request: {e:?}"))
        })?;

    let handover = Handover::new(encryption_info_base64, origin)
        .map_err(|e| DcApiError::InternalError(format!("Could not build handover: {e:?}")))?;
    let session_transcript = SessionTranscriptDCAPI::new(handover.clone());
    let session_transcript_bytes = cbor::to_vec(&session_transcript).map_err(|e| {
        DcApiError::InternalError(format!("Could not serialize session transcript: {e:?}"))
    })?;

    let mut verifier_pk_bytes = vec![4]; // uncompressed tag
    match encryption_info.1.recipient_public_key {
        CoseKey::EC2 {
            crv: EC2Curve::P256,
            mut x,
            y: EC2Y::Value(mut y),
        } => {
            verifier_pk_bytes.append(&mut x);
            verifier_pk_bytes.append(&mut y);
        }
        k => {
            return Err(DcApiError::InternalError(format!(
                "Unsupported public key: {k:?}"
            )))
        }
    }
    let verifier_pk =
        <hpke::kem::DhP256HkdfSha256 as hpke::Kem>::PublicKey::from_bytes(&verifier_pk_bytes)
            .map_err(|e| {
                DcApiError::InvalidRequest(format!("Could not decode verifier public key: {e:?}"))
            })?;

    let mut mdoc = None;
    for credential in &parsed_credentials {
        if let ParsedCredentialInner::MsoMdoc(ref m) = credential.inner {
            if m.id() == approved_response.credential_id {
                mdoc = Some(m);
                break;
            }
        }
    }
    if mdoc.is_none() {
        return Err(DcApiError::InternalError(
            "No matching credential found for the approved response in the list of credentials"
                .to_string(),
        ));
    }
    let device_response = iso_18013_7::prepare_response::prepare_response(
        key_store,
        mdoc.unwrap(),
        approved_response.approved_fields,
        &selected_match.missing_fields,
        selected_match.field_map.clone(),
        handover,
    )
    .map_err(|e| DcApiError::InternalError(format!("Could not build response document: {e:?}")))?;
    let device_response_bytes = cbor::to_vec(&device_response).map_err(|e| {
        DcApiError::InternalError(format!("Could not serialize device response: {e:?}"))
    })?;

    let (encapped_key, mut encryption_context) =
        hpke::setup_sender::<AesGcm128, HkdfSha256, DhP256HkdfSha256, _>(
            &OpModeS::Base,
            &verifier_pk,
            &session_transcript_bytes,
            &mut rand::rng(),
        )
        .map_err(|e| DcApiError::InternalError(format!("Could not set up hpke sender: {e:?}")))?;
    let cipher_text = encryption_context
        .seal(&device_response_bytes, b"")
        .map_err(|e| DcApiError::InternalError(format!("Could not encrypt response: {e:?}")))?;

    let encrypted_response_data = EncryptedResponseData {
        enc: encapped_key.to_bytes().to_vec().into(),
        cipher_text: cipher_text.into(),
    };
    let encrypted_response = EncryptedResponse("dcapi".into(), encrypted_response_data);
    let encrypted_response_bytes = cbor::to_vec(&encrypted_response).map_err(|e| {
        DcApiError::InternalError(format!(
            "Could not serialize encrypted response to cbor: {e:?}"
        ))
    })?;
    Ok(encrypted_response_bytes)
}
