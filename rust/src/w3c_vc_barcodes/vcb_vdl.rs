use std::collections::HashMap;

use sha2::{Digest, Sha256};

use crate::{
    base45_decode, cborld::decode_from_cbor_ld_to_json, w3c_vc_barcodes::VCBVerificationError,
};

#[derive(uniffi::Object, Debug)]
#[allow(dead_code)]
pub struct DecodedVcbVdl {
    cbor_value: ciborium::Value,
    json_value: serde_json::Value,
}

#[uniffi::export]
impl DecodedVcbVdl {
    pub fn cbor_value(&self) -> String {
        format!("{:#?}", self.cbor_value)
    }

    pub fn json_value(&self) -> String {
        self.json_value.to_string()
    }
}

/// Decode VDL credential from barcode string generated as defined
// in https://w3c-ccg.github.io/vc-barcodes/#birth-certificate-0
// to JSON
//
// @param barcode_string: The QR code payload of the VCB (starting with `VC1-`)
// @param contexts: A map of context URLs to their JSON content (strings)
// @return: A DecodedVcbVdl object containing the CBOR and JSON values
//
#[uniffi::export]
pub async fn decode_vcb_vdl_to_json(
    barcode_string: String,
    contexts: HashMap<String, String>,
) -> Result<DecodedVcbVdl, VCBVerificationError> {
    // 1. Remove VC1- prefix
    let payload = barcode_string
        .strip_prefix("VC1-")
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid barcode: missing VC1- prefix".to_string(),
        })?;

    // 2. Decode from base45
    let cbor_data = base45_decode(payload).map_err(|e| VCBVerificationError::Generic {
        value: e.to_string(),
    })?;

    // 3. Decode from CBOR to diagnostic notation
    let cbor_value: ciborium::Value =
        ciborium::from_reader(&cbor_data[..]).map_err(|e| VCBVerificationError::Generic {
            value: format!("Failed to parse CBOR: {}", e),
        })?;

    // 4. Decode from CBOR-LD to JSON
    let json_value = decode_from_cbor_ld_to_json(&cbor_data, contexts)
        .await
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?;

    Ok(DecodedVcbVdl {
        cbor_value,
        json_value,
    })
}

/// Extracts the public key from a DID:key identifier
fn extract_public_key_from_did(did_key: &str) -> Result<p256::PublicKey, VCBVerificationError> {
    // 1. Remove "did:key:" prefix
    let multibase_key = did_key
        .strip_prefix("did:key:")
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid DID:key format".to_string(),
        })?;

    // 2. Remove 'z' (base58-btc prefix)
    let base58_key = multibase_key
        .strip_prefix('z')
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid multibase format".to_string(),
        })?;

    // 3. Decode from base58
    let multicodec_key =
        bs58::decode(base58_key)
            .into_vec()
            .map_err(|e| VCBVerificationError::Generic {
                value: format!("Base58 decode error: {}", e),
            })?;

    // 4. Check multicodec prefix (0x8024 for P-256)
    if multicodec_key.len() < 2 || multicodec_key[0] != 0x80 || multicodec_key[1] != 0x24 {
        return Err(VCBVerificationError::Generic {
            value: "Invalid multicodec prefix for P-256 public key".to_string(),
        });
    }

    // 5. Extract compressed public key (33 bytes after multicodec prefix)
    let public_key_bytes = &multicodec_key[2..];

    // 6. Parse P-256 public key
    use p256::elliptic_curve::sec1::FromEncodedPoint;
    use p256::EncodedPoint;

    let encoded_point =
        EncodedPoint::from_bytes(public_key_bytes).map_err(|e| VCBVerificationError::Generic {
            value: format!("Invalid encoded point: {}", e),
        })?;

    let public_key = p256::PublicKey::from_encoded_point(&encoded_point);

    if public_key.is_some().into() {
        Ok(public_key.unwrap())
    } else {
        Err(VCBVerificationError::Generic {
            value: "Failed to parse public key from encoded point".to_string(),
        })
    }
}

/// Create the data to be signed for ECDSA-RDFC-2019
/// IMPORTANT: This is NOT FULLY COMPLIANT W3C RDFC-1.0 implementation that ensures
/// interoperability with other W3C VC implementations.
///
/// For full W3C RDFC-1.0 compliance with interoperability across
/// different implementations, we need to:
/// - Use JSON-LD expansion (resolve all contexts)
/// - Convert to RDF N-Quads format
/// - Apply RDFC-1.0 canonicalization algorithm
/// - Use a library like `rdf-canon` crate
///
/// Even not being fully compliant, this implementation is sufficient for our use
/// in this use case.
pub fn create_vcb_vdl_signing_input(
    credential_json: &str,
) -> Result<Vec<u8>, VCBVerificationError> {
    use serde_json::Value;
    use std::collections::BTreeMap;

    // 1. Parse JSON to ensure validity
    let json_value: Value =
        serde_json::from_str(credential_json).map_err(|e| VCBVerificationError::Generic {
            value: format!("Invalid JSON: {}", e),
        })?;

    // 2. Convert to BTreeMap to ensure alphabetical ordering
    fn sort_json_keys(value: Value) -> Value {
        match value {
            Value::Object(map) => {
                let sorted: BTreeMap<String, Value> = map
                    .into_iter()
                    .map(|(k, v)| (k, sort_json_keys(v)))
                    .collect();
                Value::Object(sorted.into_iter().collect())
            }
            Value::Array(arr) => Value::Array(arr.into_iter().map(sort_json_keys).collect()),
            other => other,
        }
    }

    let sorted_value = sort_json_keys(json_value);

    // 3. Serialize with deterministic ordering
    let normalized_json =
        serde_json::to_string(&sorted_value).map_err(|e| VCBVerificationError::Generic {
            value: format!("Failed to serialize JSON: {}", e),
        })?;

    // 4. Hash the normalized credential
    let cred_hash = Sha256::digest(normalized_json.as_bytes());

    Ok(cred_hash.to_vec())
}

/// Verify a signature against the VDL credential and public key
pub fn verify_json_signature(
    public_key: &p256::PublicKey,
    data: &[u8],
    signature_base58: &str,
) -> Result<bool, VCBVerificationError> {
    use ecdsa::VerifyingKey;
    use signature::Verifier;

    // 1. Remove 'z' prefix and decode from base58
    let sig_base58 = signature_base58
        .strip_prefix('z')
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid signature format: missing 'z' prefix".to_string(),
        })?;

    let sig_bytes =
        bs58::decode(sig_base58)
            .into_vec()
            .map_err(|e| VCBVerificationError::Generic {
                value: format!("Failed to decode signature: {}", e),
            })?;

    // 2. Parse signature
    let signature = ecdsa::Signature::<p256::NistP256>::from_slice(&sig_bytes).map_err(|e| {
        VCBVerificationError::Generic {
            value: format!("Invalid signature: {}", e),
        }
    })?;

    // 3. Get verifying key from public key
    let verifying_key = VerifyingKey::from(*public_key);

    // 4. Verify signature
    match verifying_key.verify(data, &signature) {
        Ok(_) => Ok(true),
        Err(_) => Ok(false),
    }
}

/// Verifies the cryptographic signature
#[uniffi::export]
pub fn verify_vcb_vdl_json_signature(json_string: String) -> Result<bool, VCBVerificationError> {
    let credential: serde_json::Value =
        serde_json::from_str(&json_string).map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?;

    let proof = credential
        .get("proof")
        .ok_or(VCBVerificationError::Generic {
            value: "Credential has no proof".to_string(),
        })?;

    let proof_value =
        proof
            .get("proofValue")
            .and_then(|v| v.as_str())
            .ok_or(VCBVerificationError::Generic {
                value: "Missing proofValue".to_string(),
            })?;

    let verification_method = proof
        .get("verificationMethod")
        .and_then(|v| v.as_str())
        .ok_or(VCBVerificationError::Generic {
            value: "Missing verificationMethod".to_string(),
        })?;

    // 1. Extract public key from DID
    let did_key = verification_method
        .split('#')
        .next()
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid verification method format".to_string(),
        })?;

    let public_key = extract_public_key_from_did(did_key)?;

    // 2. Serialize credential without proof
    let mut credential_without_proof = credential.clone();
    if let Some(obj) = credential_without_proof.as_object_mut() {
        obj.remove("proof");
    }
    let credential_json = serde_json::to_string(&credential_without_proof).map_err(|e| {
        VCBVerificationError::Generic {
            value: e.to_string(),
        }
    })?;

    // 3. Create signing input (hash of credential)
    let signing_input = create_vcb_vdl_signing_input(&credential_json).map_err(|e| {
        VCBVerificationError::Generic {
            value: e.to_string(),
        }
    })?;

    // 4. Verify signature
    let is_valid =
        verify_json_signature(&public_key, &signing_input, proof_value).map_err(|e| {
            VCBVerificationError::Generic {
                value: e.to_string(),
            }
        })?;

    Ok(is_valid)
}
