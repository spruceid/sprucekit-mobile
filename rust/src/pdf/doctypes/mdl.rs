use base64::prelude::*;

use crate::credential::mdoc::Mdoc;
use crate::pdf::{PdfSection, PdfSource, PdfSupplement, PdfTheme};

/// MDL-specific data extracted from an `Mdoc` ready for PDF rendering.
pub struct MdlContent {
    family_name: Option<String>,
    given_name: Option<String>,
    birth_date: Option<String>,
    document_number: Option<String>,
    expiry_date: Option<String>,
    issuing_country: Option<String>,
    issuing_authority: Option<String>,
    resident_address: Option<String>,
    resident_city: Option<String>,
    resident_state: Option<String>,
    resident_postal_code: Option<String>,
    portrait: Option<Vec<u8>>,
    /// External data provided by the Wallet at generation time (barcodes, etc.).
    pub(crate) supplements: Vec<PdfSupplement>,
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
