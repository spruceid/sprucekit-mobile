use std::sync::Arc;

use crate::credential::ParsedCredential;

pub mod doctypes;
mod render;

#[cfg(test)]
mod tests;

pub use render::PdfRenderError;

// ── Trait ────────────────────────────────────────────────────────────────────

/// A document that knows how to describe itself as a sequence of PDF sections.
/// The renderer has no knowledge of credential types or business logic.
pub trait PdfSource {
    fn document_title(&self) -> String;
    fn sections(&self) -> Vec<PdfSection>;
    fn theme(&self) -> PdfTheme {
        PdfTheme::default()
    }
}

// ── Section model ─────────────────────────────────────────────────────────────

/// A single logical piece of content to be rendered on the PDF page.
pub enum PdfSection {
    Header {
        title: String,
        subtitle: Option<String>,
    },
    KeyValueList {
        title: Option<String>,
        entries: Vec<(String, String)>,
    },
    Image {
        label: Option<String>,
        data: Vec<u8>,
        content_type: String,
    },
    TextBlock {
        title: Option<String>,
        body: String,
    },
    Barcode {
        label: Option<String>,
        data: Vec<u8>,
        barcode_type: BarcodeType,
    },
    Columns {
        left: Vec<PdfSection>,
        right: Vec<PdfSection>,
    },
    Footer {
        text: String,
    },
}

#[derive(Debug, Clone, Copy)]
pub enum BarcodeType {
    QrCode,
    Pdf417,
}

// ── Theme ─────────────────────────────────────────────────────────────────────

pub struct PdfTheme {
    /// RGB components in the range 0.0–1.0.
    pub header_color: (f32, f32, f32),
}

impl Default for PdfTheme {
    fn default() -> Self {
        // Navy: #2E4578
        Self {
            header_color: (0.18, 0.27, 0.47),
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum PdfError {
    #[error("PDF rendering failed: {0}")]
    Render(String),
}

impl From<PdfRenderError> for PdfError {
    fn from(e: PdfRenderError) -> Self {
        PdfError::Render(e.to_string())
    }
}

// ── UniFFI entry point ────────────────────────────────────────────────────────

/// Generate a PDF from a parsed credential. Returns raw PDF bytes.
///
/// **New credential type** (e.g. vehicle registration): add a `doctypes/` module
/// implementing [`PdfSource`], then add a match arm here. The renderer needs no changes.
///
/// **Non-credential PDF** (e.g. audit report, receipt): implement [`PdfSource`] on any
/// struct, call [`render::PdfRenderer::render`] directly, and expose a separate
/// `#[uniffi::export]` entry point — no need to route through this function.
///
/// **Phase 2 barcodes** (QR / PDF-417): produce the encoded bytes in the doctype and
/// return them as `PdfSection::Barcode` — picked up by the renderer automatically.
#[uniffi::export]
pub fn generate_credential_pdf(credential: Arc<ParsedCredential>) -> Result<Vec<u8>, PdfError> {
    use crate::credential::ParsedCredentialInner;
    use doctypes::mdl::MdlContent;
    use render::PdfRenderer;

    match &credential.inner {
        ParsedCredentialInner::MsoMdoc(mdoc) => {
            let content = MdlContent::from_mdoc(mdoc);
            PdfRenderer::render(&content).map_err(Into::into)
        }
        _ => Err(PdfError::Render(
            "PDF generation is only supported for mDL/mDoc credentials".to_string(),
        )),
    }
}
