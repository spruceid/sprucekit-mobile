use std::sync::Arc;

use anyhow::Context;
use base64::prelude::*;
use ciborium::Value as Cbor;
use hpke::{
    aead::AesGcm128, kdf::HkdfSha256, kem::DhP256HkdfSha256, Deserializable, OpModeS, Serializable,
};
use isomdl::{
    cbor,
    definitions::{
        device_request::{DeviceRequest, DeviceRequestInfo, ReaderAuth},
        helpers::{ByteStr, NonEmptyVec, Tag24},
        session::SessionTranscript,
        x509::{x5chain::X5CHAIN_COSE_HEADER_LABEL, X5Chain},
        CoseKey, DocRequest, EC2Curve, EC2Y,
    },
    presentation::reader::ReaderAuthenticationAll,
};
use pkcs8::der::Encode;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sha2::{Digest, Sha256};
use signature::Verifier;
use ssi::claims::cose::coset;
use tracing::warn;

use crate::{
    credential::{ParsedCredential, ParsedCredentialInner},
    crypto::KeyStore,
    oid4vp::iso_18013_7::{self, requested_values::RequestMatch180137, ApprovedResponse180137},
    verifier::crypto::{CoseP256Verifier, Crypto},
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

/// This is redundant with the verification done by the browser/OS API but is still recommended.
fn verify_reader_auth_all(
    doc_requests: NonEmptyVec<DocRequest>,
    reader_auth_all: NonEmptyVec<ReaderAuth>,
    device_request_info: Option<Tag24<DeviceRequestInfo>>,
    session_transcript: SessionTranscriptDCAPI<Handover>,
    crypto: Arc<dyn Crypto>,
) -> Result<(), DcApiError> {
    let reader_authentication_all = ReaderAuthenticationAll(
        "ReaderAuthenticationAll".into(),
        session_transcript.clone(),
        doc_requests
            .iter()
            .map(|r| r.items_request.clone())
            .collect(),
        device_request_info,
    );
    let reader_authentication_all_bytes =
        cbor::to_vec(&Tag24::new(reader_authentication_all).map_err(|e| {
            DcApiError::InternalError(format!("Failed to tag 24 reader authentication all: {e:?}"))
        })?)
        .map_err(|e| {
            DcApiError::InternalError(format!(
                "Failed to serialize reader authentication all: {e:?}"
            ))
        })?;

    for (i, auth) in reader_auth_all.iter().enumerate() {
        if let Some((_, x5c)) = auth
            .unprotected
            .rest
            .iter()
            .find(|(k, _)| *k == coset::Label::Int(X5CHAIN_COSE_HEADER_LABEL))
        {
            let signer_certificate = X5Chain::from_cbor(x5c.clone()).map_err(|e| {
                DcApiError::InvalidRequest(format!(
                    "Could not deserialize X509 chain from COSE header: {e:?}"
                ))
            })?;
            let verifier = CoseP256Verifier {
                crypto: crypto.as_ref(),
                certificate_der: signer_certificate
                    .end_entity_certificate()
                    .to_der()
                    .map_err(|e| {
                        DcApiError::InvalidRequest(format!(
                            "Unable to encode signer cert as DER: {e:?}"
                        ))
                    })?,
            };
            auth.verify_detached_signature(&reader_authentication_all_bytes, &[], |sig, data| {
                let sig = sig.try_into().context("Could not deserialize signature")?;
                verifier
                    .verify(data, &sig)
                    .context("Failed to verify signature")
            })
            .map_err(|e: anyhow::Error| {
                DcApiError::InvalidRequest(format!(
                    "Could not verify readerAuthAll at index {i}: {e:?}"
                ))
            })?;
        } else {
            warn!("Skipping reader auth verification as cose_sign1 does not contain x5c from which to retrieve public key");
        }
    }
    Ok(())
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn build_annex_c_response(
    request: Vec<u8>,
    origin: String,
    selected_match: Arc<RequestMatch180137>,
    parsed_credentials: Vec<Arc<ParsedCredential>>,
    approved_response: ApprovedResponse180137,
    key_store: Arc<dyn KeyStore>,
    crypto: Arc<dyn Crypto>,
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
    let device_request: DeviceRequest = cbor::from_slice(&device_request_bytes).map_err(|e| {
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

    if let Some(reader_auth_all) = device_request.reader_auth_all {
        verify_reader_auth_all(
            device_request.doc_requests,
            reader_auth_all,
            device_request.device_request_info,
            session_transcript,
            crypto,
        )
        .map_err(|e| {
            DcApiError::InvalidRequest(format!("Failed to verify device request: {e:?}"))
        })?;
    } else {
        warn!("Skipping reader authentication as no readerAuthAll was provided");
    }

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
