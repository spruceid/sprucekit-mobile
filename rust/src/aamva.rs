//! AAMVA PDF-417 byte encoder for mDL credentials.
//!
//! Produces the raw byte payload that [`crate::pdf::generate_credential_pdf`] renders
//! into the PDF-417 barcode of an mDL PDF export, following the AAMVA DL/ID Card
//! Design Standard (v2020). Symmetric to the decoder in [`crate::w3c_vc_barcodes`].
//!
//! Byte layout (file header, subfile designators, record separators) is delegated to
//! the upstream `w3c-vc-barcodes` crate's `FileBuilder` / `DlSubfileBuilder`. This
//! module only handles the mDL → AAMVA field mapping and the UniFFI wrapper.
//!
//! AAMVA-mandatory fields that the mDL doesn't carry (vehicle class, document
//! discriminator, name-truncation flags, …) are filled with safe placeholder
//! defaults so the encoder always produces a readable PDF-417.

use std::collections::HashMap;
use std::sync::Arc;

use ssi::security::multibase::Base;
use w3c_vc_barcodes::aamva::{
    dlid::{
        pdf_417::FileBuilder, DlElement, DlMandatoryElement, DlOptionalElement, DlSubfileBuilder,
    },
    ZZSubfile,
};

use crate::credential::{mdoc::Mdoc, ParsedCredential, ParsedCredentialInner};

// ── Public API ────────────────────────────────────────────────────────────────

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum AamvaEncodeError {
    #[error("AAMVA encoding is only supported for mDL/mDoc credentials")]
    UnsupportedCredentialType,
    #[error("missing mandatory mDL field: {0}")]
    MissingMdlField(String),
    #[error("internal encoding failure: {0}")]
    Internal(String),
}

/// Generate AAMVA-format bytes suitable for feeding into the PDF-417 renderer.
///
/// Wallets typically pass the returned bytes as a
/// [`crate::pdf::PdfSupplement::Barcode`] into
/// [`crate::pdf::generate_credential_pdf`].
///
/// # Parameters
///
/// - `credential` — an mDL (`mso_mdoc` doctype). Other credential types return
///   [`AamvaEncodeError::UnsupportedCredentialType`].
/// - `vc_barcode` — optional pre-signed **VC Barcode (VCB)** bytes per the W3C
///   [`w3c-vc-barcodes`] spec (CBOR-LD compressed, DL-field-commitment-bound).
///   **Not** a generic JWT-VC / LDP-VC / mDoc — the issuer must produce this
///   specific format (SpruceKit only receives it and passes through).
///   When `Some`, embedded as a ZZ subfile so compliant AAMVA readers can verify
///   the credential offline against the DL subfile. When `None`, only the DL
///   subfile is emitted.
///
/// [`w3c-vc-barcodes`]: https://w3c-ccg.github.io/vc-barcodes/
#[uniffi::export]
pub fn generate_aamva_pdf417_bytes(
    credential: Arc<ParsedCredential>,
    vc_barcode: Option<Vec<u8>>,
) -> Result<Vec<u8>, AamvaEncodeError> {
    let mdoc = match &credential.inner {
        ParsedCredentialInner::MsoMdoc(m) => m,
        _ => return Err(AamvaEncodeError::UnsupportedCredentialType),
    };
    encode_mdl(mdoc, vc_barcode)
}

// ── Encoding ──────────────────────────────────────────────────────────────────

/// AAMVA version 10 = Card Design Standard 2020 (latest).
const AAMVA_VERSION: u8 = 10;
const JURISDICTION_VERSION: u8 = 0;
/// Placeholder IIN until a real issuer identification number is plumbed through.
/// `0` encodes as `"000000"`, matching the decoder-side test fixture.
const PLACEHOLDER_IIN: u32 = 0;

fn encode_mdl(mdoc: &Mdoc, vc_barcode: Option<Vec<u8>>) -> Result<Vec<u8>, AamvaEncodeError> {
    let fields = extract_mdl_fields(mdoc);

    let mut b = DlSubfileBuilder::new();

    // ── Hard-required (error if missing) ─────────────────────────────────────
    let document_number = fields
        .get("document_number")
        .ok_or_else(|| AamvaEncodeError::MissingMdlField("document_number".into()))?;
    let family_name = fields
        .get("family_name")
        .ok_or_else(|| AamvaEncodeError::MissingMdlField("family_name".into()))?;
    let given_name_full = fields
        .get("given_name")
        .ok_or_else(|| AamvaEncodeError::MissingMdlField("given_name".into()))?;
    let birth_date = fields
        .get("birth_date")
        .ok_or_else(|| AamvaEncodeError::MissingMdlField("birth_date".into()))?;
    let expiry_date = fields
        .get("expiry_date")
        .ok_or_else(|| AamvaEncodeError::MissingMdlField("expiry_date".into()))?;

    let (first_name, middle_name) = split_given_name(given_name_full);

    set_mandatory(
        &mut b,
        DlMandatoryElement::CustomerIdNumber,
        document_number,
    );
    set_mandatory(&mut b, DlMandatoryElement::CustomerFamilyName, family_name);
    set_mandatory(&mut b, DlMandatoryElement::FamilyNameTruncation, "N");
    set_mandatory(&mut b, DlMandatoryElement::CustomerFirstName, &first_name);
    set_mandatory(&mut b, DlMandatoryElement::FirstNameTruncation, "N");
    set_mandatory(
        &mut b,
        DlMandatoryElement::CustomerMiddleName,
        middle_name.as_deref().unwrap_or("NONE"),
    );
    set_mandatory(&mut b, DlMandatoryElement::MiddleNameTruncation, "N");
    set_mandatory(&mut b, DlMandatoryElement::VehicleClass, "NONE");
    set_mandatory(&mut b, DlMandatoryElement::RestrictionCodes, "NONE");
    set_mandatory(&mut b, DlMandatoryElement::EndorsementCodes, "NONE");
    set_mandatory(
        &mut b,
        DlMandatoryElement::DocumentIssueDate,
        &fields
            .get("issue_date")
            .map(|s| format_aamva_date(s))
            .unwrap_or_else(|| "01011900".to_string()),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::DateOfBirth,
        &format_aamva_date(birth_date),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::DocumentExpirationDate,
        &format_aamva_date(expiry_date),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::Sex,
        &format_sex(fields.get("sex").map(String::as_str)),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::Height,
        &format_height(fields.get("height").map(String::as_str)),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::EyeColor,
        map_eye_color(fields.get("eye_colour").map(String::as_str)),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::AddressStreet1,
        &truncate(
            fields
                .get("resident_address")
                .map(String::as_str)
                .unwrap_or("UNKNOWN"),
            35,
        ),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::AddressCity,
        &truncate(
            fields
                .get("resident_city")
                .map(String::as_str)
                .unwrap_or("UNKNOWN"),
            20,
        ),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::AddressJurisdictionCode,
        &format_jurisdiction(fields.get("resident_state").map(String::as_str)),
    );
    set_mandatory(
        &mut b,
        DlMandatoryElement::AddressPostalCode,
        &format_postal_code(fields.get("resident_postal_code").map(String::as_str)),
    );
    set_mandatory(&mut b, DlMandatoryElement::DocumentDiscriminator, "UNKNOWN");
    set_mandatory(
        &mut b,
        DlMandatoryElement::CountryIdentification,
        &format_country(fields.get("issuing_country").map(String::as_str)),
    );

    // ── Optional fields (only emit if the mDL has them) ──────────────────────
    if let Some(hair) = fields.get("hair_colour") {
        set_optional(&mut b, DlOptionalElement::HairColor, hair);
    }
    if let Some(weight) = fields.get("weight") {
        // mDL "weight" is in kilograms (integer). AAMVA DAW is pounds.
        if let Ok(kg) = weight.parse::<u32>() {
            let lbs = ((kg as f64) * 2.20462).round() as u32;
            set_optional(
                &mut b,
                DlOptionalElement::WeightInPounds,
                &format!("{:03}", lbs.min(999)),
            );
        }
    }
    if let Some(birthplace) = fields.get("birth_place") {
        set_optional(
            &mut b,
            DlOptionalElement::PlaceOfBirth,
            &truncate(birthplace, 33),
        );
    }

    let dl = b.build().map_err(|e| {
        AamvaEncodeError::Internal(format!(
            "DlSubfileBuilder::build failed with missing element: {:?}",
            e.0
        ))
    })?;

    let mut file = FileBuilder::new(PLACEHOLDER_IIN, AAMVA_VERSION, JURISDICTION_VERSION);
    file.push(dl);

    // ZZ subfile: optional pre-signed VC Barcode bytes from the issuer.
    // `ZZSubfile` stores its payload as a base64url-pad string inside the
    // single `ZZA` element (see upstream `aamva/mod.rs:210` in w3c-vc-barcodes).
    if let Some(vcb_bytes) = vc_barcode {
        let zz = ZZSubfile {
            zza: Base::Base64UrlPad.encode(&vcb_bytes),
        };
        file.push(zz);
    }

    Ok(file.into_bytes())
}

fn set_mandatory(b: &mut DlSubfileBuilder, el: DlMandatoryElement, value: &str) {
    b.set(DlElement::Mandatory(el), value.as_bytes().to_vec());
}

fn set_optional(b: &mut DlSubfileBuilder, el: DlOptionalElement, value: &str) {
    b.set(DlElement::Optional(el), value.as_bytes().to_vec());
}

// ── Field extraction ─────────────────────────────────────────────────────────

/// Walk `mdoc.details()` across all namespaces, un-quote JSON string scalars,
/// and collect identifier → value into a flat map.
fn extract_mdl_fields(mdoc: &Mdoc) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for (_ns, elements) in mdoc.details() {
        for element in elements {
            let Some(raw) = element.value else { continue };
            out.insert(element.identifier, strip_json_quotes(&raw));
        }
    }
    out
}

/// Strip a leading+trailing `"` from a `serde_json::to_string_pretty`-style scalar.
/// Copied locally rather than lifted to `pdf::doctypes::mdl` to keep this module
/// independent of the PDF layer.
fn strip_json_quotes(s: &str) -> String {
    let trimmed = s.trim();
    if trimmed.starts_with('"') && trimmed.ends_with('"') && trimmed.len() >= 2 {
        trimmed[1..trimmed.len() - 1].to_string()
    } else {
        trimmed.to_string()
    }
}

// ── Value formatters ─────────────────────────────────────────────────────────

/// mDL `given_name` may contain spaces (`"John Quincy"`). AAMVA splits first and
/// middle name into separate elements (DAC, DAD). Take everything up to the first
/// space as first name, remainder as middle; no middle → `None`.
fn split_given_name(full: &str) -> (String, Option<String>) {
    match full.split_once(' ') {
        Some((first, rest)) => (truncate(first, 40), Some(truncate(rest.trim(), 40))),
        None => (truncate(full, 40), None),
    }
}

/// `YYYY-MM-DD` → `MMDDYYYY`. On malformed input, returns the input unchanged —
/// downstream decoders will flag it; there's no sane placeholder that wouldn't
/// silently hide a bug.
fn format_aamva_date(iso: &str) -> String {
    let iso = iso.trim();
    let parts: Vec<&str> = iso.split('-').collect();
    if parts.len() == 3 && parts[0].len() == 4 && parts[1].len() == 2 && parts[2].len() == 2 {
        return format!("{}{}{}", parts[1], parts[2], parts[0]);
    }
    iso.to_string()
}

/// mDL `sex` is an integer per ISO/IEC 5218 (0=not known, 1=male, 2=female,
/// 9=not applicable). AAMVA DBC accepts `"1"`, `"2"`, or `"9"`.
fn format_sex(raw: Option<&str>) -> String {
    match raw.map(str::trim) {
        Some("1") => "1".to_string(),
        Some("2") => "2".to_string(),
        _ => "9".to_string(),
    }
}

/// mDL `height` is an integer in centimeters. AAMVA DAU is fixed 6 chars
/// alphanumeric-with-spaces: `"180 CM"`. Placeholder for missing/invalid: `"000 cm"`.
fn format_height(raw: Option<&str>) -> String {
    match raw.and_then(|s| s.trim().parse::<u32>().ok()) {
        Some(cm) => format!("{:03} CM", cm.min(999)),
        None => "000 CM".to_string(),
    }
}

fn map_eye_color(raw: Option<&str>) -> &'static str {
    match raw.map(|s| s.trim().to_ascii_lowercase()).as_deref() {
        Some("black") => "BLK",
        Some("blue") => "BLU",
        Some("brown") => "BRO",
        Some("gray") | Some("grey") => "GRY",
        Some("green") => "GRN",
        Some("hazel") => "HAZ",
        Some("maroon") => "MAR",
        Some("pink") => "PNK",
        Some("dichromatic") => "DIC",
        _ => "UNK",
    }
}

fn format_jurisdiction(raw: Option<&str>) -> String {
    match raw.map(|s| s.trim().to_ascii_uppercase()) {
        Some(s) if s.len() == 2 && s.chars().all(|c| c.is_ascii_alphabetic()) => s,
        _ => "XX".to_string(),
    }
}

fn format_postal_code(raw: Option<&str>) -> String {
    let s = raw.map(|s| s.trim()).unwrap_or("");
    if s.is_empty() {
        return "00000000000".to_string();
    }
    let mut padded = s.to_string();
    // AAMVA DAK is F11Ans (fixed 11); pad right with spaces when shorter.
    if padded.len() < 11 {
        padded.push_str(&" ".repeat(11 - padded.len()));
    } else if padded.len() > 11 {
        padded.truncate(11);
    }
    padded
}

fn format_country(raw: Option<&str>) -> String {
    match raw.map(|s| s.trim().to_ascii_uppercase()).as_deref() {
        Some("US") | Some("USA") => "USA".to_string(),
        Some("CA") | Some("CAN") => "CAN".to_string(),
        Some("MX") | Some("MEX") => "MEX".to_string(),
        Some(s) if s.len() == 3 && s.chars().all(|c| c.is_ascii_alphabetic()) => s.to_string(),
        _ => "USA".to_string(),
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        s.chars().take(max).collect()
    }
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use std::io::Cursor;
    use std::sync::Arc;

    use test_log::test;
    use w3c_vc_barcodes::aamva::{
        dlid::{pdf_417::File, DlSubfile},
        ZZSubfile,
    };

    use super::*;
    use crate::credential::ParsedCredential;
    use crate::crypto::{KeyAlias, RustTestKeyManager};
    use crate::mdl::util::generate_test_mdl;

    async fn make_test_credential() -> Arc<ParsedCredential> {
        let km = RustTestKeyManager::default();
        let alias = KeyAlias("test_aamva".to_string());
        km.generate_p256_signing_key(alias.clone())
            .await
            .expect("key generation failed");
        let mdoc = generate_test_mdl(Arc::new(km), alias).expect("test mDL generation failed");
        ParsedCredential::new_mso_mdoc(Arc::new(mdoc))
    }

    /// Decode helper — parses the AAMVA byte string back into a DL subfile.
    fn decode_dl(bytes: &[u8]) -> DlSubfile {
        let payload = String::from_utf8(bytes.to_vec()).expect("valid utf-8");
        let mut cursor = Cursor::new(payload);
        let mut file = File::new(&mut cursor).expect("File::new failed");
        file.read_subfile::<DlSubfile>(b"DL")
            .expect("read_subfile DL failed")
            .expect("no DL subfile")
    }

    fn get_mandatory(dl: &DlSubfile, el: DlMandatoryElement) -> &str {
        std::str::from_utf8(dl.get(DlElement::Mandatory(el)).expect("element present"))
            .expect("utf-8")
    }

    #[test(tokio::test)]
    async fn roundtrip_test_mdl() {
        let credential = make_test_credential().await;
        let bytes = generate_aamva_pdf417_bytes(credential, None).expect("encode");

        // Must start with the AAMVA prefix bytes.
        assert!(
            bytes.starts_with(b"@\n\x1e\rANSI "),
            "missing AAMVA prefix, got {:?}",
            &bytes[..bytes.len().min(16)]
        );

        let dl = decode_dl(&bytes);

        // Values from generate_test_mdl defaults (rust/src/mdl/util.rs:193)
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::CustomerFamilyName),
            "Doe"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::CustomerFirstName),
            "John"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::FamilyNameTruncation),
            "N"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::FirstNameTruncation),
            "N"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::MiddleNameTruncation),
            "N"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::CustomerMiddleName),
            "NONE"
        );
        assert_eq!(get_mandatory(&dl, DlMandatoryElement::VehicleClass), "NONE");
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::RestrictionCodes),
            "NONE"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::EndorsementCodes),
            "NONE"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::DateOfBirth),
            "01011990"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::DocumentIssueDate),
            "01012020"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::DocumentExpirationDate),
            "01012030"
        );
        assert_eq!(get_mandatory(&dl, DlMandatoryElement::Sex), "1");
        assert_eq!(get_mandatory(&dl, DlMandatoryElement::Height), "180 CM");
        assert_eq!(get_mandatory(&dl, DlMandatoryElement::EyeColor), "BLU");
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::AddressCity),
            "Los Angeles"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::AddressJurisdictionCode),
            "CA"
        );
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::CountryIdentification),
            "USA"
        );

        // DAQ is populated with a random doc number "DL<8 digits>" — just check format.
        let daq = get_mandatory(&dl, DlMandatoryElement::CustomerIdNumber);
        assert!(daq.starts_with("DL"), "DAQ should start with DL, got {daq}");

        // DAK is padded to 11 chars.
        let dak = get_mandatory(&dl, DlMandatoryElement::AddressPostalCode);
        assert_eq!(dak.len(), 11);
        assert!(dak.starts_with("90001"));

        // DCF always present as placeholder.
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::DocumentDiscriminator),
            "UNKNOWN"
        );

        // Optional hair color should come through.
        let hair = dl.get(DlElement::Optional(DlOptionalElement::HairColor));
        assert_eq!(hair, Some(&b"black"[..]));

        // No ZZ subfile when vc_barcode is None.
        let payload = String::from_utf8(bytes.clone()).expect("utf-8");
        let mut cursor = Cursor::new(payload);
        let file = File::new(&mut cursor).expect("File::new");
        assert!(
            file.index_of(b"ZZ").is_none(),
            "ZZ subfile should be absent when vc_barcode is None"
        );
    }

    #[test(tokio::test)]
    async fn roundtrip_with_zz_subfile() {
        use ssi::{
            claims::data_integrity::ProofOptions,
            dids::{AnyDidMethod, DIDKey, DIDResolver},
            security::multibase::Base,
            verification_methods::SingleSecretSigner,
            JWK,
        };
        use w3c_vc_barcodes::{
            optical_barcode_credential::{create, encode_to_bytes, SignatureParameters},
            MachineReadableZone,
        };

        // Sign a minimal MRZ-type VCB with a randomly generated key.
        // The DL subfile is real; the VCB is just here to exercise the ZZ path —
        // in production it would come pre-signed from the issuer.
        let jwk = JWK::generate_p256();
        let vm = DIDKey::generate_url(&jwk).expect("did:key gen");
        let options = ProofOptions::from_method(vm.into_iri().into());
        let params = SignatureParameters::new(
            AnyDidMethod::default().into_vm_resolver(),
            SingleSecretSigner::new(jwk),
            None,
        );
        let mrz_data: [[u8; 30]; 3] = [
            *b"IAUTO0000007010SRC0000000701<<",
            *b"8804192M2601058NOT<<<<<<<<<<<5",
            *b"SMITH<<JOHN<<<<<<<<<<<<<<<<<<<",
        ];
        let issuer = "http://example.org/issuer".parse().expect("valid uri");
        let vc = create(&mrz_data, issuer, MachineReadableZone {}, options, params)
            .await
            .expect("VCB signing");
        let vcb_bytes = encode_to_bytes(&vc).await;

        // Encode with ZZ subfile.
        let credential = make_test_credential().await;
        let bytes =
            generate_aamva_pdf417_bytes(credential, Some(vcb_bytes.clone())).expect("encode");

        // Decode DL first (unchanged behaviour).
        let dl = decode_dl(&bytes);
        assert_eq!(
            get_mandatory(&dl, DlMandatoryElement::CustomerFamilyName),
            "Doe"
        );

        // Decode ZZ and verify it round-trips back to the original VCB bytes.
        let payload = String::from_utf8(bytes).expect("utf-8");
        let mut cursor = Cursor::new(payload);
        let mut file = File::new(&mut cursor).expect("File::new");
        assert!(
            file.index_of(b"ZZ").is_some(),
            "ZZ subfile should be present"
        );
        let zz: ZZSubfile = file
            .read_subfile(b"ZZ")
            .expect("read_subfile ZZ")
            .expect("no ZZ subfile");

        let decoded_bytes = Base::Base64UrlPad
            .decode(&zz.zza)
            .expect("zza is base64url-pad");
        assert_eq!(
            decoded_bytes, vcb_bytes,
            "ZZ subfile payload should round-trip the original VCB bytes verbatim"
        );
    }

    #[test]
    fn date_formatting() {
        assert_eq!(format_aamva_date("1990-01-15"), "01151990");
        assert_eq!(format_aamva_date("2030-12-31"), "12312030");
        assert_eq!(format_aamva_date("not-a-date"), "not-a-date");
    }

    #[test]
    fn sex_formatting() {
        assert_eq!(format_sex(Some("1")), "1");
        assert_eq!(format_sex(Some("2")), "2");
        assert_eq!(format_sex(Some("0")), "9");
        assert_eq!(format_sex(None), "9");
    }

    #[test]
    fn height_formatting() {
        assert_eq!(format_height(Some("180")), "180 CM");
        assert_eq!(format_height(Some("5")), "005 CM");
        assert_eq!(format_height(None), "000 CM");
        assert_eq!(format_height(Some("abc")), "000 CM");
    }

    #[test]
    fn eye_color_mapping() {
        assert_eq!(map_eye_color(Some("blue")), "BLU");
        assert_eq!(map_eye_color(Some("BROWN")), "BRO");
        assert_eq!(map_eye_color(Some("teal")), "UNK");
        assert_eq!(map_eye_color(None), "UNK");
    }

    #[test]
    fn country_mapping() {
        assert_eq!(format_country(Some("US")), "USA");
        assert_eq!(format_country(Some("ca")), "CAN");
        assert_eq!(format_country(Some("MEX")), "MEX");
        assert_eq!(format_country(None), "USA");
    }

    #[test]
    fn postal_code_padding() {
        assert_eq!(format_postal_code(Some("90001")), "90001      ");
        assert_eq!(format_postal_code(Some("901234567890")), "90123456789");
        assert_eq!(format_postal_code(None), "00000000000");
    }

    #[test]
    fn given_name_split() {
        assert_eq!(
            split_given_name("John Quincy Adams"),
            ("John".to_string(), Some("Quincy Adams".to_string()))
        );
        assert_eq!(split_given_name("John"), ("John".to_string(), None));
    }
}
