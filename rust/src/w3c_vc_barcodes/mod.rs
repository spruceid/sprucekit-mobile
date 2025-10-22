use std::io::Cursor;

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
    optical_barcode_credential::{decode_from_bytes, VerificationParameters},
    terse_bitstring_status_list_entry::{ConstTerseStatusListProvider, StatusListInfo},
    verify, MachineReadableZone, MRZ,
};

mod vcb_vdl;

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
    use std::collections::HashMap;

    use super::*;

    #[tokio::test]
    async fn verify_vcb_dl() {
        let pdf417 = "@\n\x1e\rANSI 000000090002DL00410234ZZ02750202DLDAQF987654321\nDCSSMITH\nDDEN\nDACJOHN\nDDFN\nDADNONE\nDDGN\nDCAC\nDCBNONE\nDCDNONE\nDBD01012024\nDBB04191988\nDBA04192030\nDBC1\nDAU069 IN\nDAYBRO\nDAG123 MAIN ST\nDAIANYVILLE\nDAJUTO\nDAKF87P20000  \nDCFUTODOCDISCRIM\nDCGUTO\nDAW158\nDCK1234567890\nDDAN\rZZZZA2QZkpgGDGYAAGYABGYACGJ2CGHYYpBi4oxicGKYYzhiyGNAa5ZIggRi6ohicGKAYqER1ggAgGL4YqhjApRicGGwY1gQY4BjmGOJYQXq3wuVrSeLM5iGEziaBjhWosXMWRAG107uT_9bSteuPasCXFQKuPdSdF-xmUoFkA0yRJoW4ERvATNyewT263ZHMGOQYrA==\r";
        verify_pdf417_barcode(pdf417.into()).await.unwrap()
    }

    #[tokio::test]
    async fn verify_vcb_employment_authorization() {
        let mrz = include_str!("../../tests/res/mrz-vcb");
        let ead = include_str!("../../tests/res/ead-vcb");
        verify_vcb_qrcode_against_mrz(mrz.into(), ead.into())
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn test_verify_vcb_vdl() {
        let barcode_string = "VC1-SJRI90Q80L8FA9DIWENPEJ/5S4F03F53F7*5$KE$:5CPEXPC  C1$CBWEAECCPEI.ED4FMWEW9E0G7106LM6-TC04E02DN/EKUDI.E1534KG$-E6MKS$MT43O8FAVC8UCB$CBECP9E*ZCY CN.CRICM-C*96Y CIOCG-CEOC4:6UZCVG6EOCD961-J853:5346M0XJYAKYPRBZCYDPL63APD*96DL6WW66:6FA7P637F35+3%P5T5TET85ZCJSTKI949D-637F35+3%P5K+U:+9K/E2VCP34/Y9073IJCXMAV50P2EQF6Y347ECM-DYOAZKE7$CB73WD0NT9.OEM-A53DZOEZ50IJCXMA$50$ZCGA7746B73V21T+9B73-B1S536MKFYHE73T70/L4%O4U:8J55P628RT S5P6O*AKO$S31J9:9ULCQFDFMHDFIW0HNTF*43K8F:+9K/E2VCP343$CGECFWE1$CSUEX3EY3424E04E/34+ COEDTVDYOE QN053.33B73S$2Z CKVC$CCYKEL.C5W5746$97I60B73X 4K6037BJKFW*DCIV2DU*EBOK5R83ZTMN8S0ER:I9L2FH1M0R2*4LKSRDNI.SJ8H629B%2FJYB.H95-QMWVD.5PGPG+5JHT7MKW1MU*7Y9IM60 PGZM0I6B:8G/C0EE4AEGXQVDQ5H.JAEW2Z3 W2LQFT6V7VNTNIM/CHF26IJ3AR/L4%O4U:8J55P628RT S5P6O*AKO$S31J9:9ULCQFDFMHDFIW0HNTF+537F35+3%P5:1PBZCYDP";

        // Load contexts from test files
        let credentials_v2 = include_str!("../../tests/context/w3_org_ns_credentials_v2.json");
        let vdl_v2 = include_str!("../../tests/context/w3id_org_vdl_v2.json");

        let mut contexts = HashMap::new();
        contexts.insert(
            "https://www.w3.org/ns/credentials/v2".to_string(),
            credentials_v2.to_string(),
        );
        contexts.insert("https://w3id.org/vdl/v2".to_string(), vdl_v2.to_string());

        // Decode the VDL credential to JSON
        let decoded = vcb_vdl::decode_vcb_vdl_to_json(barcode_string.to_string(), contexts)
            .await
            .unwrap();

        // println!("{:#?}", decoded);

        // Verify signature
        let is_valid =
            vcb_vdl::verify_vcb_vdl_json_signature(decoded.json_value.to_string()).unwrap();

        assert!(is_valid);
    }
}
