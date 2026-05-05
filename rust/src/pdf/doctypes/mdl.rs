use std::sync::Arc;

use base64::prelude::*;

use crate::credential::mdoc::Mdoc;
use crate::credential::vcdm2_sd_jwt::{SdJwtError, VCDM2SdJwt};
use crate::pdf::{PdfSection, PdfSource, PdfSupplement, PdfTheme};

/// MDL-specific data extracted from an `Mdoc` ready for PDF rendering.
#[derive(serde::Deserialize, serde::Serialize)]
pub struct MdlContent {
    #[serde(default)]
    family_name: Option<String>,
    #[serde(default)]
    given_name: Option<String>,
    #[serde(default)]
    birth_date: Option<String>,
    #[serde(default)]
    document_number: Option<String>,
    #[serde(default)]
    expiry_date: Option<String>,
    #[serde(default)]
    issuing_country: Option<String>,
    #[serde(default)]
    issuing_authority: Option<String>,
    #[serde(default)]
    resident_address: Option<String>,
    #[serde(default)]
    resident_city: Option<String>,
    #[serde(default)]
    resident_state: Option<String>,
    #[serde(default)]
    resident_postal_code: Option<String>,
    #[serde(default, deserialize_with = "deserialize_portrait_data_url")]
    portrait: Option<Vec<u8>>,
    #[serde(skip)]
    /// External data provided by the Wallet at generation time (barcodes, etc.).
    pub(crate) supplements: Vec<PdfSupplement>,
}

impl TryFrom<&Arc<VCDM2SdJwt>> for MdlContent {
    type Error = SdJwtError;

    /// Build an `MdlContent` from a VCDM2 SD-JWT credential.
    ///
    /// Navigates to the `credentialSubject.driversLicense` subobject (per the
    /// VDL JSON-LD context) before deserializing into our flat `MdlContent`
    /// shape. Fields not present in the revealed claims (e.g. portrait when
    /// holder hides it for QR encoding) are deserialized as `None`.
    fn try_from(value: &Arc<VCDM2SdJwt>) -> Result<Self, Self::Error> {
        let claims = value.revealed_claims_as_json()?;
        let dl = claims
            .pointer("/credentialSubject/driversLicense")
            .ok_or_else(|| {
                SdJwtError::Serialization(
                    "missing credentialSubject.driversLicense in SD-JWT".to_string(),
                )
            })?;
        serde_json::from_value(dl.clone()).map_err(|e| SdJwtError::Serialization(e.to_string()))
    }
}

impl MdlContent {
    /// Extract mDL fields from an `Mdoc` using its `details()` API.
    pub fn from_mdoc(mdoc: &Mdoc) -> Self {
        let details = mdoc.details();
        // `Namespace` is a UniFFI newtype without a public string accessor.
        // Flatten across all namespaces — in a well-formed mDL, the identifiers we
        // match below (family_name, given_name, …) only exist in org.iso.18013.5.1.
        let elements: Vec<_> = details.values().flatten().collect();

        let mut content = MdlContent {
            family_name: None,
            given_name: None,
            birth_date: None,
            document_number: None,
            expiry_date: None,
            issuing_country: None,
            issuing_authority: None,
            resident_address: None,
            resident_city: None,
            resident_state: None,
            resident_postal_code: None,
            portrait: None,
            supplements: Vec::new(),
        };

        for element in &elements {
            let Some(ref raw) = element.value else {
                continue;
            };
            match element.identifier.as_str() {
                "family_name" => content.family_name = Some(strip_json_quotes(raw)),
                "given_name" => content.given_name = Some(strip_json_quotes(raw)),
                "birth_date" => content.birth_date = Some(strip_json_quotes(raw)),
                "document_number" => content.document_number = Some(strip_json_quotes(raw)),
                "expiry_date" => content.expiry_date = Some(strip_json_quotes(raw)),
                "issuing_country" => content.issuing_country = Some(strip_json_quotes(raw)),
                "issuing_authority" => content.issuing_authority = Some(strip_json_quotes(raw)),
                "resident_address" => content.resident_address = Some(strip_json_quotes(raw)),
                "resident_city" => content.resident_city = Some(strip_json_quotes(raw)),
                "resident_state" => content.resident_state = Some(strip_json_quotes(raw)),
                "resident_postal_code" => {
                    content.resident_postal_code = Some(strip_json_quotes(raw))
                }
                "portrait" => content.portrait = decode_portrait(raw),
                _ => {}
            }
        }

        content
    }

    fn full_name(&self) -> String {
        match (&self.given_name, &self.family_name) {
            (Some(g), Some(f)) => format!("{g} {f}"),
            (Some(g), None) => g.clone(),
            (None, Some(f)) => f.clone(),
            (None, None) => "—".to_string(),
        }
    }

    fn address_line(&self) -> Option<String> {
        let parts: Vec<&str> = [
            self.resident_address.as_deref(),
            self.resident_city.as_deref(),
            self.resident_state.as_deref(),
            self.resident_postal_code.as_deref(),
        ]
        .into_iter()
        .flatten()
        .collect();

        if parts.is_empty() {
            None
        } else {
            Some(parts.join(", "))
        }
    }
}

impl PdfSource for MdlContent {
    fn document_title(&self) -> String {
        "MOBILE DRIVER'S LICENSE".to_string()
    }

    fn sections(&self) -> Vec<PdfSection> {
        let mut sections = Vec::new();

        // ── Header ─────────────────────────────────────────────────────────
        sections.push(PdfSection::Header {
            title: self.document_title(),
            subtitle: self.issuing_authority.clone(),
        });

        // ── Portrait + core fields ──────────────────────────────────────────
        let mut core_entries: Vec<(String, String)> = Vec::new();
        core_entries.push(("Name".to_string(), self.full_name()));
        if let Some(dob) = &self.birth_date {
            core_entries.push(("Date of Birth".to_string(), dob.clone()));
        }
        if let Some(num) = &self.document_number {
            core_entries.push(("Document No.".to_string(), num.clone()));
        }
        if let Some(exp) = &self.expiry_date {
            core_entries.push(("Expires".to_string(), exp.clone()));
        }
        if let Some(country) = &self.issuing_country {
            core_entries.push(("Issuing Country".to_string(), country.clone()));
        }
        if let Some(auth) = &self.issuing_authority {
            core_entries.push(("Issuing Authority".to_string(), auth.clone()));
        }

        let left: Vec<PdfSection> = if let Some(portrait_data) = &self.portrait {
            vec![PdfSection::Image {
                label: Some("Portrait".to_string()),
                data: portrait_data.clone(),
                content_type: "image/jpeg".to_string(),
            }]
        } else {
            vec![]
        };

        let right = vec![PdfSection::KeyValueList {
            title: None,
            entries: core_entries,
        }];

        sections.push(PdfSection::Columns { left, right });

        // ── Address ────────────────────────────────────────────────────────
        if let Some(addr) = self.address_line() {
            sections.push(PdfSection::KeyValueList {
                title: Some("Address".to_string()),
                entries: vec![("Address".to_string(), addr)],
            });
        }

        // ── Barcodes (from supplements) ────────────────────────────────────
        for sup in &self.supplements {
            match sup {
                PdfSupplement::Barcode { data, barcode_type } => {
                    sections.push(PdfSection::Barcode {
                        label: None,
                        data: data.clone(),
                        barcode_type: *barcode_type,
                    });
                }
            }
        }

        // ── Footer ─────────────────────────────────────────────────────────
        sections.push(PdfSection::Footer {
            text: "Generated from a digital credential — not an official document".to_string(),
        });

        sections
    }

    fn theme(&self) -> PdfTheme {
        PdfTheme::default()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Remove surrounding JSON string quotes from a scalar value produced by
/// `serde_json::to_string_pretty` on a `serde_json::Value::String`.
///
/// `"\"John\""` → `"John"`.  Non-quoted values (numbers, booleans) pass through unchanged.
pub(crate) fn strip_json_quotes(s: &str) -> String {
    let trimmed = s.trim();
    if trimmed.starts_with('"') && trimmed.ends_with('"') && trimmed.len() >= 2 {
        trimmed[1..trimmed.len() - 1].to_string()
    } else {
        trimmed.to_string()
    }
}

/// Decode portrait JPEG bytes from the data-URL string stored in the `Mdoc` element value.
///
/// `Mdoc::details()` represents byte-string elements as `"data:image/jpeg;base64,<b64>"`.
fn decode_portrait(raw: &str) -> Option<Vec<u8>> {
    let stripped = strip_json_quotes(raw);

    // Prefer image/jpeg prefix (set by Mdoc::details replacement)
    for prefix in &[
        "data:image/jpeg;base64,",
        "data:application/octet-stream;base64,",
    ] {
        if let Some(b64) = stripped.strip_prefix(prefix) {
            if let Ok(bytes) = BASE64_STANDARD.decode(b64) {
                return Some(bytes);
            }
        }
    }
    None
}

/// Serde deserializer for the `portrait` field when the source is a JSON
/// data-URL string (as produced by SD-JWT issuance).
///
/// Accepts either `null`, a missing field, or a `"data:image/jpeg;base64,…"`
/// string. Returns `None` if the field is absent, null, or unparsable —
/// rendering the PDF without a portrait rather than failing the whole flow
/// (consistent with the mDoc path's `decode_portrait` behavior).
fn deserialize_portrait_data_url<'de, D>(deserializer: D) -> Result<Option<Vec<u8>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::Deserialize;
    let opt: Option<String> = Option::deserialize(deserializer)?;
    Ok(opt.and_then(|s| {
        // `decode_portrait` expects a JSON-quoted string (matches the mDoc path
        // where values come from `serde_json::to_string_pretty`). Re-quote to
        // reuse the same parser.
        decode_portrait(&format!("\"{s}\""))
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::credential::{
        format::vcdm2_sd_jwt::{tests::generate_mdl_sd_jwt, VCDM2SdJwt},
        ParsedCredential,
    };
    use crate::pdf::generate_credential_pdf;

    /// `MdlContent::try_from(&Arc<VCDM2SdJwt>)` should pull the expected leaf
    /// values out of the SD-JWT's `credentialSubject.driversLicense` subtree
    /// and decode the portrait data-URL into raw bytes.
    #[tokio::test]
    async fn try_from_sd_jwt_extracts_fields() {
        let sd_jwt_buf: ssi::claims::sd_jwt::SdJwtBuf = generate_mdl_sd_jwt().await;
        let parsed: Arc<VCDM2SdJwt> = VCDM2SdJwt::new_from_compact_sd_jwt(sd_jwt_buf.to_string())
            .expect("parse fixture SD-JWT");

        let content = MdlContent::try_from(&parsed).expect("MdlContent::try_from");

        assert_eq!(content.family_name.as_deref(), Some("ONEZERO"));
        assert_eq!(content.given_name.as_deref(), Some("IRVINGTEST"));
        assert_eq!(content.birth_date.as_deref(), Some("1999-03-16"));
        assert_eq!(content.document_number.as_deref(), Some("I8882610"));
        assert_eq!(content.expiry_date.as_deref(), Some("2028-03-16"));
        assert_eq!(content.issuing_country.as_deref(), Some("US"));
        assert_eq!(content.issuing_authority.as_deref(), Some("CA,USA"));
        assert_eq!(content.resident_address.as_deref(), Some("2415 1ST AVE"));
        assert_eq!(content.resident_city.as_deref(), Some("SACRAMENTO"));
        assert_eq!(content.resident_state.as_deref(), Some("CA"));
        assert_eq!(content.resident_postal_code.as_deref(), Some("95818"));

        // Portrait data-URL should decode to non-empty bytes.
        let portrait = content.portrait.as_ref().expect("portrait decoded");
        assert!(!portrait.is_empty(), "portrait bytes should be non-empty");
        // First two bytes of the placeholder JPEG (after base64-decoding
        // `/9j/2w...`) are the JPEG SOI marker `0xFF 0xD8`.
        assert_eq!(&portrait[0..2], &[0xFF, 0xD8]);
    }

    /// Going all the way through `generate_credential_pdf` from a SD-JWT
    /// credential should produce a valid PDF — the same end-to-end smoke
    /// guarantee we already have for the mDoc path.
    #[tokio::test]
    async fn sd_jwt_credential_renders_to_pdf() {
        let sd_jwt_buf: ssi::claims::sd_jwt::SdJwtBuf = generate_mdl_sd_jwt().await;
        let sd_jwt = VCDM2SdJwt::new_from_compact_sd_jwt(sd_jwt_buf.to_string())
            .expect("parse fixture SD-JWT");
        let credential = ParsedCredential::new_sd_jwt(sd_jwt);

        let pdf_bytes = generate_credential_pdf(credential, vec![]).expect("PDF generation");

        assert!(
            pdf_bytes.starts_with(b"%PDF-"),
            "output should start with PDF magic bytes"
        );
        assert!(
            pdf_bytes.len() > 1024,
            "PDF should be non-trivial (got {} bytes)",
            pdf_bytes.len()
        );
    }
}
