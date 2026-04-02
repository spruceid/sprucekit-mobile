use std::sync::Arc;

use test_log::test;

use crate::{
    credential::ParsedCredential,
    crypto::{KeyAlias, RustTestKeyManager},
    mdl::util::generate_test_mdl,
    pdf::{doctypes::mdl::MdlContent, generate_credential_pdf, render::PdfRenderer},
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
    let pdf_bytes = generate_credential_pdf(credential).expect("PDF generation failed");

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
