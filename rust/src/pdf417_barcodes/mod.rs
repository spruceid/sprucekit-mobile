use base64::Engine;
use p256::{
    ecdsa::{signature::Verifier, Signature, VerifyingKey},
    pkcs8::DecodePublicKey,
};
use sha2::{Digest, Sha256};
use std::collections::HashMap;

use crate::w3c_vc_barcodes::VCBVerificationError;

#[derive(uniffi::Object, Debug)]
pub struct DecodedPdf417Aamva {
    dl_fields: Vec<(String, String)>,
    zn_fields: Vec<(String, String)>,
}

#[uniffi::export]
impl DecodedPdf417Aamva {
    /// Returns a JSON string of the DL (driver license) fields as key-value pairs
    /// See pages 53-56 here for their meanings: 
    /// https://aamva.org/getmedia/99ac7057-0f4d-4461-b0a2-3a5532e1b35c/AAMVA-2020-DLID-Card-Design-Standard.pdf
    pub fn dl_fields_json(&self) -> String {
        let map: HashMap<String, String> = self.dl_fields.iter().cloned().collect();
        serde_json::to_string_pretty(&map).unwrap_or_default()
    }

    /// Returns a JSON string of the ZN (Nevada jurisdiction-specific) fields
    /// This should just be a signature and doesn't need to be displayed to the uesr
    pub fn zn_fields_json(&self) -> String {
        let map: HashMap<String, String> = self.zn_fields.iter().cloned().collect();
        serde_json::to_string_pretty(&map).unwrap_or_default()
    }

    /// Returns a comprehensive JSON with all parsed data
    pub fn full_json(&self) -> String {
        let mut full_map = serde_json::Map::new();

        let dl_map: HashMap<String, String> = self.dl_fields.iter().cloned().collect();
        full_map.insert("dl_fields".to_string(), serde_json::to_value(dl_map).unwrap());

        let zn_map: HashMap<String, String> = self.zn_fields.iter().cloned().collect();
        full_map.insert("zn_fields".to_string(), serde_json::to_value(zn_map).unwrap());

        serde_json::to_string_pretty(&full_map).unwrap_or_default()
    }
}

/// Decode a PDF417 barcode from raw payload string
///
/// @param payload: The raw AAMVA payload string
/// @return: A DecodedPdf417Aamva object containing raw and parsed data
#[uniffi::export]
pub fn decode_pdf417_aamva_from_payload(
    payload: String,
) -> Result<DecodedPdf417Aamva, VCBVerificationError> {
    // Parse the AAMVA payload
    let (dl_fields, zn_fields) = parse_aamva_payload(&payload)?;

    Ok(DecodedPdf417Aamva {
        dl_fields,
        zn_fields,
    })
}

/// Verify the P-256 signature in the ZN subfile
///
/// @param decoded: The decoded AAMVA payload
/// @param public_key_pem: The public key in PEM format
/// @return: true if signature is valid, false otherwise
#[uniffi::export]
pub fn verify_pdf417_aamva_signature(
    decoded: &DecodedPdf417Aamva,
    public_key_pem: String,
) -> Result<bool, VCBVerificationError> {
    // Find signature in ZN subfile
    let sig_base64 = decoded
        .zn_fields
        .iter()
        .find(|(k, _)| k == "ZSA")
        .map(|(_, v)| v.clone())
        .ok_or(VCBVerificationError::Generic {
            value: "No signature field (ZSA) found in ZN subfile".to_string(),
        })?;

    // Load public key
    let verifying_key = VerifyingKey::from_public_key_pem(&public_key_pem).map_err(|e| {
        VCBVerificationError::Generic {
            value: format!("Failed to parse public key: {}", e),
        }
    })?;

    // Decode signature
    let sig_bytes = base64::engine::general_purpose::STANDARD
        .decode(&sig_base64)
        .map_err(|e| VCBVerificationError::Generic {
            value: format!("Failed to decode signature: {}", e),
        })?;
    let signature = Signature::from_slice(&sig_bytes).map_err(|e| VCBVerificationError::Generic {
        value: format!("Failed to parse signature: {}", e),
    })?;

    // Build data to verify (all DL fields concatenated)
    let mut data_to_verify = String::new();
    for (key, value) in &decoded.dl_fields {
        data_to_verify.push_str(key);
        data_to_verify.push_str(value);
    }

    // Hash the data
    let mut hasher = Sha256::new();
    hasher.update(data_to_verify.as_bytes());
    let hash = hasher.finalize();

    // Verify signature
    match verifying_key.verify(&hash, &signature) {
        Ok(_) => Ok(true),
        Err(_) => Ok(false),
    }
}

fn parse_aamva_payload(
    payload: &str,
) -> Result<(Vec<(String, String)>, Vec<(String, String)>), VCBVerificationError> {
    let mut dl_fields = Vec::new();
    let mut zn_fields = Vec::new();

    // Find the DL subfile - it's the second occurrence of "DL" (first is in the header designator)
    let mut dl_start = 0;
    let mut occurrences = 0;
    for (i, _) in payload.match_indices("DL") {
        occurrences += 1;
        if occurrences == 2 {
            dl_start = i;
            break;
        }
    }
    if occurrences < 2 {
        return Err(VCBVerificationError::Generic {
            value: "No DL subfile found".to_string(),
        });
    }

    // Find where DL subfile ends (at CR) and ZN subfile starts
    let dl_data_start = dl_start + 2; // Skip "DL"
    let zn_start = payload[dl_data_start..].find("ZN").map(|i| dl_data_start + i);

    // Parse DL subfile
    let dl_end = if let Some(zn_pos) = zn_start {
        zn_pos
    } else {
        payload.len()
    };
    let dl_data = &payload[dl_data_start..dl_end];

    let parts: Vec<&str> = dl_data.split('\n').collect();
    for part in parts {
        if part.len() >= 3 && !part.ends_with('\r') {
            let key = &part[0..3];
            let value = &part[3..];
            if key.starts_with('D') {
                dl_fields.push((key.to_string(), value.to_string()));
            }
        } else if part.len() >= 3 && part.ends_with('\r') {
            let trimmed = part.trim_end_matches('\r');
            if trimmed.len() >= 3 {
                let key = &trimmed[0..3];
                let value = &trimmed[3..];
                if key.starts_with('D') {
                    dl_fields.push((key.to_string(), value.to_string()));
                }
            }
            break;
        }
    }

    // Parse ZN subfile if it exists
    if let Some(zn_pos) = zn_start {
        let zn_data = &payload[zn_pos + 2..]; // Skip "ZN"

        // ZN subfile is simple - just parse until CR
        if let Some(cr_pos) = zn_data.find('\r') {
            let zn_content = &zn_data[..cr_pos];
            // Parse ZN fields (currently just ZSA)
            if zn_content.len() >= 3 {
                let key = &zn_content[0..3];
                let value = &zn_content[3..];
                if key.starts_with('Z') {
                    zn_fields.push((key.to_string(), value.to_string()));
                }
            }
        }
    }

    Ok((dl_fields, zn_fields))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_decode_and_verify_pdf417_aamva_from_payload() {
        // Test with the actual payload from the generated barcode
        let payload = "@\n\x1e\rANSI 636000100002DL00410201ZN02420094DLDCAC\nDCBNONE\nDCDNONE\nDBA01012030\nDCSChang\nDACWayne\nDADNONE\nDBD01012025\nDBB01011990\nDBC1\nDAYBRO\nDAU070 in\nDAW180\nDAZBLK\nDAG123 Main St\nDAIAnytown\nDAJNV\nDAK12345\nDAQA0000000\nDCF1\nDCGUSA\nDDEN\nDDFN\nDDGN\rZNZSAYpmtnv6AY8IaVm4ss6gvAnij4i1iYjfUrlffsBAfLuk7Ap9NiiTQNdBE9Wayigjnw2vTJ9ckL8sZk/POp+uS1A==\r";

        let decoded = decode_pdf417_aamva_from_payload(payload.to_string()).unwrap();

        // Test DL fields
        let dl_json = decoded.dl_fields_json();
        assert!(!dl_json.is_empty());
        println!("\nDL Fields JSON:\n{}", dl_json);

        // Verify some expected fields
        assert_eq!(decoded.dl_fields.iter().find(|(k, _)| k == "DCS").map(|(_, v)| v.as_str()), Some("Chang"));
        assert_eq!(decoded.dl_fields.iter().find(|(k, _)| k == "DAC").map(|(_, v)| v.as_str()), Some("Wayne"));
        assert_eq!(decoded.dl_fields.iter().find(|(k, _)| k == "DAJ").map(|(_, v)| v.as_str()), Some("NV"));

        // Test ZN fields
        let zn_json = decoded.zn_fields_json();
        assert!(!zn_json.is_empty());
        println!("\nZN Fields JSON:\n{}", zn_json);

        // Verify signature field exists
        assert!(decoded.zn_fields.iter().any(|(k, _)| k == "ZSA"));

        // Test full JSON
        let full_json = decoded.full_json();
        assert!(!full_json.is_empty());
        println!("\nFull JSON:\n{}", full_json);

        // Verify signature
        let public_key_pem = include_str!("../../tests/res/pdf417_nevada_public_key.pem");
        let is_valid = verify_pdf417_aamva_signature(&decoded, public_key_pem.to_string()).unwrap();
        assert!(is_valid, "Signature should be valid");
        println!("\n✓ Signature is VALID");

        println!("Decoded {} DL fields and {} ZN fields", decoded.dl_fields.len(), decoded.zn_fields.len());
    }
}
