use std::sync::Arc;

use test_log::test;

use crate::{
    credential::{
        format::optical_barcode_credential::{
            generate_test_optical_barcode_credential, OpticalBarcodeCred,
        },
        ParsedCredential,
    },
    crypto::{KeyAlias, RustTestKeyManager},
    mdl::util::generate_test_mdl,
    pdf::{
        doctypes::mdl::MdlContent, generate_credential_pdf, render::PdfRenderer, BarcodeType,
        PdfSupplement,
    },
};

/// Build a test Mdoc using the in-memory key manager and hardcoded test data.
async fn make_test_mdoc() -> crate::credential::mdoc::Mdoc {
    let km = RustTestKeyManager::default();
    let alias = KeyAlias("test_pdf".to_string());
    km.generate_p256_signing_key(alias.clone())
        .await
        .expect("key generation failed");
    generate_test_mdl(Arc::new(km), alias).expect("test mDL generation failed")
}

#[test(tokio::test)]
async fn test_mdl_to_pdf_produces_valid_pdf_bytes() {
    let mdoc = make_test_mdoc().await;
    let credential = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));
    let pdf_bytes = generate_credential_pdf(credential, vec![])
        .await
        .expect("PDF generation failed");

    assert!(
        pdf_bytes.starts_with(b"%PDF-"),
        "output should start with PDF magic bytes"
    );
    assert!(
        pdf_bytes.len() > 1024,
        "output should be a non-trivial PDF (got {} bytes)",
        pdf_bytes.len()
    );
}

#[test(tokio::test)]
async fn test_mdl_fields_extracted_for_pdf() {
    let mdoc = make_test_mdoc().await;

    let content = MdlContent::from_mdoc(&mdoc);

    // Render through the full pipeline to verify no panics
    let pdf_bytes = PdfRenderer::render(&content).expect("PDF rendering failed");
    assert!(pdf_bytes.starts_with(b"%PDF-"));
    assert!(pdf_bytes.len() > 1024);

    // Verify specific field values via the credential details API
    let details = mdoc.details();
    let elements: Vec<_> = details.values().flatten().collect();

    let given = elements
        .iter()
        .find(|e| e.identifier == "given_name")
        .and_then(|e| e.value.as_deref())
        .expect("given_name missing");
    assert!(
        given.contains("John"),
        "given_name should contain 'John', got: {given}"
    );

    let family = elements
        .iter()
        .find(|e| e.identifier == "family_name")
        .and_then(|e| e.value.as_deref())
        .expect("family_name missing");
    assert!(
        family.contains("Doe"),
        "family_name should contain 'Doe', got: {family}"
    );
}

#[test(tokio::test)]
async fn test_qr_barcode_renders() {
    let mdoc = make_test_mdoc().await;
    let credential = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));

    let qr_data = b"https://example.com/credential/12345".to_vec();
    let supplements = vec![PdfSupplement::Barcode {
        data: qr_data,
        barcode_type: BarcodeType::QrCode,
    }];

    let pdf_bytes = generate_credential_pdf(credential, supplements)
        .await
        .expect("PDF with QR failed");

    assert!(
        pdf_bytes.starts_with(b"%PDF-"),
        "output should start with PDF magic bytes"
    );
    // PDF with a QR code image should be larger than without
    assert!(
        pdf_bytes.len() > 2048,
        "PDF with QR should be non-trivial (got {} bytes)",
        pdf_bytes.len()
    );
}

#[test(tokio::test)]
async fn test_pdf417_barcode_renders() {
    let mdoc = make_test_mdoc().await;
    let credential = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));

    let pdf417_data = b"given_name=John\nfamily_name=Doe\nbirth_date=1990-01-15".to_vec();
    let supplements = vec![PdfSupplement::Barcode {
        data: pdf417_data,
        barcode_type: BarcodeType::Pdf417,
    }];

    let pdf_bytes = generate_credential_pdf(credential, supplements)
        .await
        .expect("PDF with PDF-417 failed");

    assert!(
        pdf_bytes.starts_with(b"%PDF-"),
        "output should start with PDF magic bytes"
    );
    assert!(
        pdf_bytes.len() > 2048,
        "PDF with PDF-417 should be non-trivial (got {} bytes)",
        pdf_bytes.len()
    );
}

#[test(tokio::test)]
async fn test_both_barcodes_render() {
    let mdoc = make_test_mdoc().await;
    let credential = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));

    let supplements = vec![
        PdfSupplement::Barcode {
            data: b"https://example.com/vp/token123".to_vec(),
            barcode_type: BarcodeType::QrCode,
        },
        PdfSupplement::Barcode {
            data: b"given_name=John\nfamily_name=Doe".to_vec(),
            barcode_type: BarcodeType::Pdf417,
        },
    ];

    let pdf_bytes = generate_credential_pdf(credential, supplements)
        .await
        .expect("PDF with both barcodes failed");

    assert!(pdf_bytes.starts_with(b"%PDF-"));
    assert!(
        pdf_bytes.len() > 3000,
        "PDF with both barcodes should be substantial (got {} bytes)",
        pdf_bytes.len()
    );
}

/// End-to-end: feed an `OpticalBarcodeCredential` supplement through
/// `generate_credential_pdf` and verify that the SDK's preprocess pipeline
/// produces a PDF-417 byte stream containing a ZZ subfile whose ZZA round-trips
/// back through CBOR-LD decode to the original JSON-LD VC.
///
/// Tests the full chain wired up in commit 2:
///   `OpticalBarcodeCred` → `ParsedCredential` → `PdfSupplement` →
///   `preprocess_supplements` → CBOR-LD encode (via `w3c_vc_barcodes`
///   bundled context loader) → AAMVA assembly (DL + ZZ subfiles) →
///   PDF-417 raw bytes → ZZ extraction → CBOR-LD decode → VC.
#[test(tokio::test)]
async fn test_vcb_pdf417_e2e_roundtrip() {
    use std::io::Cursor;
    use w3c_vc_barcodes::{
        aamva::{dlid::pdf_417, ZZSubfile},
        optical_barcode_credential::decode_from_bytes,
        MachineReadableZone,
    };

    // 1. Generate a freshly-signed test VCB (MachineReadableZone) as JSON-LD.
    let jsonld = generate_test_optical_barcode_credential()
        .await
        .expect("generate test VCB");

    // 2. Wrap as an OpticalBarcodeCredential ParsedCredential.
    let vcb_inner = OpticalBarcodeCred::new(jsonld.clone()).expect("parse VCB JSON-LD");
    let vcb_pc = ParsedCredential::new_optical_barcode_credential(vcb_inner);

    // 3. Build a host mDL ParsedCredential.
    let mdoc = make_test_mdoc().await;
    let mdl_pc = ParsedCredential::new_mso_mdoc(Arc::new(mdoc));

    // 4. Run the preprocess pipeline directly (private to `pdf` module).
    let supplements = vec![PdfSupplement::OpticalBarcodeCredential { credential: vcb_pc }];
    let preprocessed = super::preprocess_supplements(&mdl_pc, supplements)
        .await
        .expect("preprocess");
    assert_eq!(preprocessed.len(), 1, "one supplement in, one out");

    // 5. Confirm we got a PDF-417 Barcode supplement back.
    let (pdf417_bytes, barcode_type) = match &preprocessed[0] {
        PdfSupplement::Barcode { data, barcode_type } => (data.clone(), *barcode_type),
        _ => panic!("expected Barcode variant after preprocess"),
    };
    assert!(
        matches!(barcode_type, BarcodeType::Pdf417),
        "preprocessed supplement should be PDF-417"
    );

    // 6. Parse AAMVA file and read ZZ subfile.
    let payload = String::from_utf8(pdf417_bytes).expect("AAMVA bytes are utf-8");
    let mut cursor = Cursor::new(payload);
    let mut file = pdf_417::File::new(&mut cursor).expect("parse AAMVA file");
    assert!(
        file.index_of(b"DL").is_some(),
        "DL subfile should be present"
    );
    assert!(
        file.index_of(b"ZZ").is_some(),
        "ZZ subfile should be present"
    );
    let zz: ZZSubfile = file
        .read_subfile(b"ZZ")
        .expect("read ZZ subfile")
        .expect("ZZ subfile present");

    // 7. ZZA = base64url-pad(CBOR-LD bytes).
    let cborld = ssi::security::multibase::Base::Base64UrlPad
        .decode(&zz.zza)
        .expect("ZZA is valid base64url-pad");
    assert!(!cborld.is_empty(), "CBOR-LD bytes should be non-empty");

    // 8. Decode CBOR-LD back to a JSON-LD VC and confirm the structure
    //    matches the original (i.e. our context loader successfully
    //    round-tripped the credential).
    let decoded_vc = decode_from_bytes::<MachineReadableZone>(&cborld)
        .await
        .expect("CBOR-LD decode → VC");

    // Sanity: the decoded VC should carry the same credentialSubject type
    // as what we signed.
    let original: serde_json::Value =
        serde_json::from_str(&jsonld).expect("original JSON-LD parses as JSON");
    let decoded: serde_json::Value =
        serde_json::to_value(&decoded_vc).expect("decoded VC serializes to JSON");

    // The exact bytes won't be identical (CBOR-LD compression may reorder /
    // re-emit fields), but key claims must agree.
    assert_eq!(
        original.pointer("/credentialSubject/type"),
        decoded.pointer("/credentialSubject/type"),
        "credentialSubject.type should round-trip"
    );
    assert_eq!(
        original.pointer("/issuer"),
        decoded.pointer("/issuer"),
        "issuer should round-trip"
    );
}
