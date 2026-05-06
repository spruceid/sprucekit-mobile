//! W3C OpticalBarcodeCredential as a registrable credential variant.
//!
//! The wallet stores the issuer-signed JSON-LD VC verbatim. CBOR-LD encoding
//! and AAMVA ZZ-subfile assembly happen later in the PDF pipeline (see
//! [`crate::cborld`] and [`crate::aamva`]); this struct is the minimal
//! "credential of record" form so that VCBs can flow through the same
//! `ParsedCredential` pipeline as mDoc / SD-JWT / etc.
//!
//! Construction performs minimal validation: parse to JSON, extract the
//! `type` claim. Signature verification is *not* attempted here — the wallet
//! receives this credential pre-signed from the issuer and verification is
//! the verifier's responsibility (see [`crate::w3c_vc_barcodes`]).

use std::sync::Arc;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

use crate::credential::{Credential, CredentialFormat};
use crate::crypto::KeyAlias;
use crate::CredentialType;

/// Default credential type when the JSON-LD `type` array does not list a more
/// specific value (or in addition to `VerifiableCredential`).
const DEFAULT_TYPE: &str = "OpticalBarcodeCredential";

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Object)]
pub struct OpticalBarcodeCred {
    id: Uuid,
    /// Verbatim JSON-LD VC bytes as received from the issuer.
    raw_jsonld: String,
    /// Cached `type` value, extracted from the JSON-LD `type` array. The most
    /// specific (non-`VerifiableCredential`) entry wins; falls back to
    /// `OpticalBarcodeCredential` if the array contains only generic types.
    cred_type: CredentialType,
}

#[uniffi::export]
impl OpticalBarcodeCred {
    /// Parse a JSON-LD OpticalBarcodeCredential. A fresh UUID is assigned.
    #[uniffi::constructor]
    pub fn new(jsonld: String) -> Result<Arc<Self>, OpticalBarcodeCredError> {
        Self::new_with_id(Uuid::new_v4(), jsonld)
    }

    /// Parse a JSON-LD OpticalBarcodeCredential with a caller-supplied UUID.
    /// Used when re-hydrating from storage (see `TryFrom<Credential>`).
    #[uniffi::constructor]
    pub fn new_with_id(id: Uuid, jsonld: String) -> Result<Arc<Self>, OpticalBarcodeCredError> {
        let value: Value = serde_json::from_str(&jsonld)
            .map_err(|e| OpticalBarcodeCredError::InvalidJsonLd(e.to_string()))?;
        let cred_type = extract_type(&value)?;
        Ok(Arc::new(Self {
            id,
            raw_jsonld: jsonld,
            cred_type,
        }))
    }

    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Verbatim JSON-LD VC string, as received from the issuer.
    pub fn raw_jsonld(&self) -> String {
        self.raw_jsonld.clone()
    }

    pub fn r#type(&self) -> CredentialType {
        self.cred_type.clone()
    }

    /// Optical-barcode credentials are issuer-signed and not re-presented by
    /// the wallet, so there is no associated holder key.
    pub fn key_alias(&self) -> Option<KeyAlias> {
        None
    }
}

fn extract_type(value: &Value) -> Result<CredentialType, OpticalBarcodeCredError> {
    let types = value
        .get("type")
        .ok_or(OpticalBarcodeCredError::MissingType)?;

    let chosen = match types {
        Value::String(s) => s.clone(),
        Value::Array(items) => {
            // Prefer the first non-`VerifiableCredential` string; otherwise
            // default to `OpticalBarcodeCredential`.
            let mut specific: Option<String> = None;
            let mut seen_any_string = false;
            for item in items {
                if let Some(s) = item.as_str() {
                    seen_any_string = true;
                    if s != "VerifiableCredential" {
                        specific = Some(s.to_string());
                        break;
                    }
                }
            }
            if !seen_any_string {
                return Err(OpticalBarcodeCredError::MissingType);
            }
            specific.unwrap_or_else(|| DEFAULT_TYPE.to_string())
        }
        _ => return Err(OpticalBarcodeCredError::MissingType),
    };

    Ok(CredentialType(chosen))
}

impl OpticalBarcodeCred {
    /// Re-hydrate from generic [`Credential`] storage form. Mirrors the
    /// `TryFrom<Credential>` impls used by every other credential format.
    pub(crate) fn from_credential(
        credential: Credential,
    ) -> Result<Arc<Self>, OpticalBarcodeCredError> {
        let jsonld = String::from_utf8(credential.payload)
            .map_err(|e| OpticalBarcodeCredError::InvalidJsonLd(e.to_string()))?;
        Self::new_with_id(credential.id, jsonld)
    }

    /// Encode as the generic [`Credential`] form for storage.
    pub(crate) fn to_credential(&self) -> Credential {
        Credential {
            id: self.id,
            format: CredentialFormat::OpticalBarcodeCredential,
            r#type: self.cred_type.clone(),
            payload: self.raw_jsonld.as_bytes().to_vec(),
            key_alias: None,
        }
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum OpticalBarcodeCredError {
    #[error("invalid JSON-LD: {0}")]
    InvalidJsonLd(String),
    #[error("missing or malformed `type` field")]
    MissingType,
    #[error("test VCB generation failed: {0}")]
    TestGenerationFailed(String),
}

/// Generate a freshly-signed test `OpticalBarcodeCredential` (MachineReadableZone
/// type) and return it as a JSON-LD string.
///
/// Mirrors the fixture used by [`crate::aamva`]'s `roundtrip_with_zz_subfile`
/// test: a randomly generated P-256 key + `did:key` issuer signs a minimal MRZ
/// VCB. Three-platform demos use this so they can exercise the full PDF-417
/// VCB pipeline before real DMV microservices ship — same key-handling /
/// signing flow as production, just with a throwaway key.
///
/// The MRZ data baked in matches the test fixture in `aamva.rs`, so the same
/// VCB can be verified against the same MRZ.
///
/// The work runs on a dedicated 8 MB-stack thread because ssi's data-integrity
/// proof signing recurses through JSON-LD context expansion deep enough to
/// blow iOS's default ~512 KB child-thread stack.
#[uniffi::export(async_runtime = "tokio")]
pub async fn generate_test_optical_barcode_credential() -> Result<String, OpticalBarcodeCredError> {
    crate::big_stack::run_async(|| async move { sign_test_vcb().await })
        .await
        .map_err(|e| {
            OpticalBarcodeCredError::TestGenerationFailed(format!("big-stack thread: {e}"))
        })?
}

async fn sign_test_vcb() -> Result<String, OpticalBarcodeCredError> {
    use ssi::{
        claims::data_integrity::ProofOptions,
        dids::{AnyDidMethod, DIDKey, DIDResolver},
        verification_methods::SingleSecretSigner,
        JWK,
    };
    use w3c_vc_barcodes::{
        optical_barcode_credential::{create, SignatureParameters},
        MachineReadableZone,
    };

    let jwk = JWK::generate_p256();
    let vm = DIDKey::generate_url(&jwk)
        .map_err(|e| OpticalBarcodeCredError::TestGenerationFailed(format!("did:key gen: {e}")))?;
    let options = ProofOptions::from_method(vm.into_iri().into());
    let params = SignatureParameters::new(
        AnyDidMethod::default().into_vm_resolver(),
        SingleSecretSigner::new(jwk),
        None,
    );

    // Same MRZ shape used by the aamva.rs roundtrip test, so the resulting
    // VCB can be verified against this MRZ in downstream tests.
    let mrz_data: [[u8; 30]; 3] = [
        *b"IAUTO0000007010SRC0000000701<<",
        *b"8804192M2601058NOT<<<<<<<<<<<5",
        *b"SMITH<<JOHN<<<<<<<<<<<<<<<<<<<",
    ];
    let issuer = "http://example.org/issuer".parse().map_err(|e| {
        OpticalBarcodeCredError::TestGenerationFailed(format!("invalid issuer URI: {e}"))
    })?;

    let vc = create(&mrz_data, issuer, MachineReadableZone {}, options, params)
        .await
        .map_err(|e| OpticalBarcodeCredError::TestGenerationFailed(format!("VCB signing: {e}")))?;

    serde_json::to_string(&vc).map_err(|e| {
        OpticalBarcodeCredError::TestGenerationFailed(format!("serialize to JSON-LD: {e}"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_jsonld() -> String {
        // A minimal JSON-LD VCB approximating what the
        // `optical_barcode_credential::create` flow would emit. The shape is
        // intentionally simplified — we don't verify or re-sign here, we only
        // exercise the field-extraction path.
        r#"{
            "@context": [
                "https://www.w3.org/ns/credentials/v2",
                "https://w3id.org/vc-barcodes/v1"
            ],
            "type": ["VerifiableCredential", "OpticalBarcodeCredential"],
            "issuer": "did:key:z6Mki",
            "credentialSubject": {
                "type": "MachineReadableZone"
            }
        }"#
        .to_string()
    }

    #[test]
    fn parse_jsonld_extracts_type() {
        let cred = OpticalBarcodeCred::new(sample_jsonld()).expect("parse");
        assert_eq!(cred.r#type().0, "OpticalBarcodeCredential");
        assert!(cred.key_alias().is_none());
        assert_eq!(cred.raw_jsonld(), sample_jsonld());
    }

    #[test]
    fn parse_string_type() {
        let json = r#"{"type": "OpticalBarcodeCredential"}"#.to_string();
        let cred = OpticalBarcodeCred::new(json).expect("parse");
        assert_eq!(cred.r#type().0, "OpticalBarcodeCredential");
    }

    #[test]
    fn invalid_jsonld_errors() {
        let err = OpticalBarcodeCred::new("not json".to_string()).expect_err("should fail");
        assert!(matches!(err, OpticalBarcodeCredError::InvalidJsonLd(_)));
    }

    #[test]
    fn missing_type_errors() {
        let json = r#"{"@context": []}"#.to_string();
        let err = OpticalBarcodeCred::new(json).expect_err("should fail");
        assert!(matches!(err, OpticalBarcodeCredError::MissingType));
    }

    #[test]
    fn roundtrip_via_credential() {
        let original = OpticalBarcodeCred::new(sample_jsonld()).expect("parse");
        let credential = original.to_credential();
        assert_eq!(
            credential.format,
            CredentialFormat::OpticalBarcodeCredential
        );
        assert_eq!(credential.r#type.0, "OpticalBarcodeCredential");
        assert!(credential.key_alias.is_none());

        let rehydrated = OpticalBarcodeCred::from_credential(credential).expect("rehydrate");
        assert_eq!(rehydrated.id(), original.id());
        assert_eq!(rehydrated.r#type().0, original.r#type().0);
        assert_eq!(rehydrated.raw_jsonld(), original.raw_jsonld());
    }

    #[test]
    fn type_array_prefers_specific() {
        let json = r#"{"type": ["VerifiableCredential", "MyCustomVCB"]}"#.to_string();
        let cred = OpticalBarcodeCred::new(json).expect("parse");
        assert_eq!(cred.r#type().0, "MyCustomVCB");
    }
}
