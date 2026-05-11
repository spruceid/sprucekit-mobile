use std::sync::Arc;

use crate::aamva::{generate_aamva_pdf417_bytes, AamvaEncodeError};
use crate::credential::{ParsedCredential, ParsedCredentialInner};
use crate::w3c_vc_barcodes::{encode_optical_barcode_credential_for_pdf417, VcbEncodingError};

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

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum BarcodeType {
    QrCode,
    Pdf417,
}

/// External data that the Wallet provides at PDF-generation time.
///
/// Barcode payloads (VP Token bytes, AAMVA bytes, etc.) are *derived* data —
/// they don't belong inside `ParsedCredential`.  `PdfSupplement` is the
/// extensible carrier that keeps the function signature stable: adding a new
/// variant here never changes the public API.
#[derive(uniffi::Enum)]
pub enum PdfSupplement {
    /// Pre-encoded barcode bytes — fed straight into the renderer as a
    /// `PdfSection::Barcode`. Use this when the wallet already has the
    /// barcode payload (e.g. a QR-code-encoded SD-JWT VP, or AAMVA bytes
    /// produced by an external tool).
    Barcode {
        data: Vec<u8>,
        barcode_type: BarcodeType,
    },
    /// W3C OpticalBarcodeCredential (VCB) — SDK encodes JSON-LD →
    /// CBOR-LD using the bundled `w3c-vc-barcodes` context loader, then
    /// assembles a complete AAMVA PDF-417 (DL subfile + ZZ subfile with
    /// the CBOR-LD as ZZA). The host credential must be an mDL.
    ///
    /// This is the recommended path for issuer-signed VCBs — wallets pass
    /// the credential directly without touching CBOR-LD or AAMVA primitives.
    OpticalBarcodeCredential { credential: Arc<ParsedCredential> },
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
    /// Raised when a `PdfSupplement::OpticalBarcodeCredential` is supplied
    /// but the host credential isn't an mDL — VCB embedding piggybacks on
    /// the AAMVA DL subfile, so we can't assemble a PDF-417 without it.
    #[error("OpticalBarcodeCredential supplement requires an mDL host credential")]
    HostCredentialNotMdl,
    /// Wraps the supplement-side `OpticalBarcodeCredential` parse failure
    /// (the supplement carried a `ParsedCredential` that wasn't actually
    /// of `OpticalBarcodeCredential` format).
    #[error("OpticalBarcodeCredential supplement is not a VCB credential")]
    SupplementNotOpticalBarcodeCredential,
    #[error("OpticalBarcodeCredential CBOR-LD encoding failed: {0}")]
    OpticalBarcodeCredentialEncoding(String),
    #[error("AAMVA PDF-417 encoding failed: {0}")]
    AamvaEncoding(String),
}

impl From<PdfRenderError> for PdfError {
    fn from(e: PdfRenderError) -> Self {
        PdfError::Render(e.to_string())
    }
}

impl From<VcbEncodingError> for PdfError {
    fn from(e: VcbEncodingError) -> Self {
        PdfError::OpticalBarcodeCredentialEncoding(e.to_string())
    }
}

impl From<AamvaEncodeError> for PdfError {
    fn from(e: AamvaEncodeError) -> Self {
        PdfError::AamvaEncoding(e.to_string())
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
/// **Barcodes** (QR / PDF-417): pass barcode payloads via `supplements`.
/// The doctype translates them into `PdfSection::Barcode` — picked up by the
/// renderer automatically.
///
/// **VCBs** (`PdfSupplement::OpticalBarcodeCredential`): SDK encodes
/// JSON-LD → CBOR-LD and assembles the AAMVA PDF-417 internally.  Because
/// CBOR-LD encoding is async, this function is async too.  Sync callers
/// providing only `PdfSupplement::Barcode` can ignore the await.
#[uniffi::export(async_runtime = "tokio")]
pub async fn generate_credential_pdf(
    credential: Arc<ParsedCredential>,
    supplements: Vec<PdfSupplement>,
) -> Result<Vec<u8>, PdfError> {
    use doctypes::mdl::MdlContent;
    use render::PdfRenderer;

    // Resolve any "high-level" supplements (e.g. OpticalBarcodeCredential)
    // into pre-encoded `Barcode` form before handing off to the doctype.
    // This keeps `MdlContent` / `PdfSource` sync — the async edge stops here.
    let supplements = preprocess_supplements(&credential, supplements).await?;

    match &credential.inner {
        ParsedCredentialInner::MsoMdoc(mdoc) => {
            let mut content = MdlContent::from_mdoc(mdoc);
            content.supplements = supplements;
            PdfRenderer::render(&content).map_err(Into::into)
        }
        ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => {
            let mut content =
                MdlContent::try_from(sd_jwt).map_err(|e| PdfError::Render(e.to_string()))?;
            content.supplements = supplements;
            PdfRenderer::render(&content).map_err(Into::into)
        }
        _ => Err(PdfError::Render(
            "PDF generation is only supported for mDL/mDoc credentials".to_string(),
        )),
    }
}

/// Resolve supplements that need async work (CBOR-LD encoding, AAMVA assembly)
/// into the renderer-ready `PdfSupplement::Barcode` form.
///
/// Currently the only "high-level" supplement is
/// `OpticalBarcodeCredential` — others pass through unchanged.
async fn preprocess_supplements(
    host: &ParsedCredential,
    sups: Vec<PdfSupplement>,
) -> Result<Vec<PdfSupplement>, PdfError> {
    let mut out = Vec::with_capacity(sups.len());
    for sup in sups {
        match sup {
            PdfSupplement::OpticalBarcodeCredential { credential } => {
                // VCB embedding piggybacks on the DL subfile — we can only
                // do this when the host credential is an mDL.
                let host_mdoc = host.as_mso_mdoc().ok_or(PdfError::HostCredentialNotMdl)?;
                let _ = host_mdoc; // captured implicitly by Arc<ParsedCredential> below

                let vcb = credential
                    .as_optical_barcode_credential()
                    .ok_or(PdfError::SupplementNotOpticalBarcodeCredential)?;
                let cborld = encode_optical_barcode_credential_for_pdf417(vcb.raw_jsonld()).await?;
                let pdf417_bytes = generate_aamva_pdf417_bytes(host.clone().into(), Some(cborld))?;
                out.push(PdfSupplement::Barcode {
                    data: pdf417_bytes,
                    barcode_type: BarcodeType::Pdf417,
                });
            }
            other => out.push(other),
        }
    }
    Ok(out)
}
