use std::{io::Cursor, str::FromStr};

use cbor_ld::JsonValue;
use ssi::{
    dids::{AnyDidMethod, DIDResolver},
    json_ld::iref::Uri,
    status::{
        bitstring_status_list::{BitstringStatusListCredential, StatusList, TimeToLive},
        bitstring_status_list_20240406::{
            BitstringStatusListCredential as BitstringStatusListCredential20240406,
            StatusList as StatusList20240406, StatusPurpose, StatusSize,
            TimeToLive as TimeToLive20240406,
        },
        client::{MaybeCached, ProviderError, TypedStatusMapProvider},
    },
};
use w3c_vc_barcodes::{
    aamva::{
        dlid::{pdf_417, DlSubfile},
        ZZSubfile,
    },
    optical_barcode_credential::{decode_from_bytes, VerificationParameters, CONTEXT_LOADER},
    terse_bitstring_status_list_entry::{ConstTerseStatusListProvider, StatusListInfo},
    verify, MachineReadableZone, MRZ,
};

#[uniffi::export]
pub async fn verify_pdf417_barcode(payload: String) -> Result<(), VCBVerificationError> {
    let mut cursor = Cursor::new(payload);
    let mut file = pdf_417::File::new(&mut cursor).map_err(|e| VCBVerificationError::Generic {
        value: e.to_string(),
    })?;
    let dl: DlSubfile = file
        .read_subfile(b"DL")
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid DLSubfile".to_string(),
        })?;
    let zz: ZZSubfile = file
        .read_subfile(b"ZZ")
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?
        .ok_or(VCBVerificationError::Generic {
            value: "Invalid ZZSubfile".to_string(),
        })?;
    let vc = zz
        .decode_credential()
        .await
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?;

    let status_list_client = ConstTerseStatusListProvider::new(
        StatusLists,
        StatusListInfo::new(1000, StatusPurpose::Revocation),
    );

    let params = VerificationParameters::new_with(
        AnyDidMethod::default().into_vm_resolver(),
        status_list_client,
    );

    verify(&vc, &dl.mandatory, params)
        .await
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?
        .map_err(|_| VCBVerificationError::Verification)
}

fn convert_to_mrz_entry(s: &[u8]) -> Result<[u8; 30], VCBVerificationError> {
    s.try_into().map_err(|_| VCBVerificationError::Generic {
        value: "error trying to convert to mrz: slice with incorrect length".to_string(),
    })
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum VCBVerificationError {
    #[error("{value}")]
    Generic { value: String },
    #[error("verification failed")]
    Verification,
}

// ── VCB encoder ──────────────────────────────────────────────────────────────

/// Encode a JSON-LD `OpticalBarcodeCredential` to CBOR-LD bytes ready for
/// embedding as a PDF-417 ZZ subfile (ZZA field).
///
/// Uses the bundled context loader from the upstream `w3c-vc-barcodes` crate
/// (`CONTEXT_LOADER`), which already includes the five contexts required by
/// VCBs: `credentials/v2`, `vc-barcodes/v1`, `utopia/v2`, `vdl/v2`,
/// `citizenship/v2`.  Wallets do **not** need to provide their own loader.
///
/// Wallets typically don't call this directly — they pass the
/// `OpticalBarcodeCredential` through
/// [`crate::pdf::PdfSupplement::OpticalBarcodeCredential`] and
/// [`crate::pdf::generate_credential_pdf`] handles encoding internally. This
/// function is exposed as a public API for advanced use cases (e.g. testing,
/// or wallets that want to cache CBOR-LD bytes).
///
/// The work runs on a dedicated 8 MB-stack thread because cbor-ld's JSON-LD
/// context expansion recurses deep enough to blow iOS's default ~512 KB
/// child-thread stack.
#[uniffi::export(async_runtime = "tokio")]
pub async fn encode_optical_barcode_credential_for_pdf417(
    jsonld: String,
) -> Result<Vec<u8>, VcbEncodingError> {
    crate::big_stack::run_async(move || async move {
        let doc = JsonValue::from_str(&jsonld)
            .map_err(|e| VcbEncodingError::JsonParse(e.to_string()))?;
        cbor_ld::encode_to_bytes(&doc, &*CONTEXT_LOADER)
            .await
            .map_err(|e| VcbEncodingError::CborEncode(e.to_string()))
    })
    .await
    .map_err(|e| VcbEncodingError::CborEncode(format!("big-stack thread: {e}")))?
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VcbEncodingError {
    #[error("JSON-LD parse error: {0}")]
    JsonParse(String),
    #[error("CBOR-LD encode error: {0}")]
    CborEncode(String),
}

#[uniffi::export]
pub async fn verify_vcb_qrcode_against_mrz(
    mrz_payload: String,
    qr_payload: String,
) -> Result<(), VCBVerificationError> {
    let mrz: MRZ = mrz_payload
        .lines()
        .map(|x| convert_to_mrz_entry(x.as_bytes()))
        .collect::<Result<Vec<[u8; 30]>, _>>()?
        .try_into()
        .map_err(|_| VCBVerificationError::Generic {
            value: "Invalid MRZ string".to_string(),
        })?;

    // First we decode the QR-code payload to get the VCB in CBOR-LD form.
    let input = MachineReadableZone::decode_qr_code_payload(qr_payload.as_str()).map_err(|e| {
        VCBVerificationError::Generic {
            value: e.to_string(),
        }
    })?;

    // Then we decompress the CBOR-LD VCB to get a regular JSON-LD VCB.
    let vc = decode_from_bytes::<MachineReadableZone>(&input)
        .await
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?;

    // Finally we verify the VCB against the MRZ data.
    let params = VerificationParameters::new(AnyDidMethod::default().into_vm_resolver());
    verify(&vc, &mrz, params)
        .await
        .map_err(|e| VCBVerificationError::Generic {
            value: e.to_string(),
        })?
        .map_err(|_| VCBVerificationError::Verification)
}

pub struct StatusLists;

impl TypedStatusMapProvider<Uri, BitstringStatusListCredential> for StatusLists {
    async fn get_typed(&self, _: &Uri) -> Result<MaybeCached<StatusList>, ProviderError> {
        // @TODO: replace with a valid status list verification when a valid test is available
        Ok(MaybeCached::NotCached(StatusList::from_bytes(
            vec![0u8; 125],
            TimeToLive::DEFAULT,
        )))
    }
}

impl TypedStatusMapProvider<Uri, BitstringStatusListCredential20240406> for StatusLists {
    async fn get_typed(&self, _: &Uri) -> Result<MaybeCached<StatusList20240406>, ProviderError> {
        // @TODO: replace with a valid status list verification when a valid test is available
        Ok(MaybeCached::NotCached(StatusList20240406::from_bytes(
            StatusSize::DEFAULT,
            vec![0u8; 125],
            TimeToLive20240406::DEFAULT,
        )))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn verify_vcb_dl() {
        let pdf417 = "@\n\x1e\rANSI 000000090002DL00410234ZZ02750202DLDAQF987654321\nDCSSMITH\nDDEN\nDACJOHN\nDDFN\nDADNONE\nDDGN\nDCAC\nDCBNONE\nDCDNONE\nDBD01012024\nDBB04191988\nDBA04192030\nDBC1\nDAU069 IN\nDAYBRO\nDAG123 MAIN ST\nDAIANYVILLE\nDAJUTO\nDAKF87P20000  \nDCFUTODOCDISCRIM\nDCGUTO\nDAW158\nDCK1234567890\nDDAN\rZZZZA2QZkpgGDGYAAGYABGYACGJ2CGHYYpBi4oxicGKYYzhiyGNAa5ZIggRi6ohicGKAYqER1ggAgGL4YqhjApRicGGwY1gQY4BjmGOJYQXq3wuVrSeLM5iGEziaBjhWosXMWRAG107uT_9bSteuPasCXFQKuPdSdF-xmUoFkA0yRJoW4ERvATNyewT263ZHMGOQYrA==\r";
        verify_pdf417_barcode(pdf417.into()).await.unwrap()
    }

    #[tokio::test]
    async fn verify_vcb_employment_authorization() {
        let mrz = include_str!("../tests/res/mrz-vcb");
        let ead = include_str!("../tests/res/ead-vcb");
        verify_vcb_qrcode_against_mrz(mrz.into(), ead.into())
            .await
            .unwrap()
    }
}
