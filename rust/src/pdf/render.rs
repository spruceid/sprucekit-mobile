use std::io::BufWriter;

use printpdf::{
    path::{PaintMode, WindingOrder},
    BuiltinFont, Color, ColorBits, ColorSpace, Image, ImageTransform, ImageXObject,
    IndirectFontRef, Mm, PdfDocument, PdfLayerReference, Px, Rect, Rgb,
};

use super::{PdfSection, PdfSource, PdfTheme};

// ── Page geometry (A4) ────────────────────────────────────────────────────────
const PAGE_W: f32 = 210.0; // mm
const PAGE_H: f32 = 297.0; // mm
const MARGIN: f32 = 15.0; // mm
const CONTENT_W: f32 = PAGE_W - 2.0 * MARGIN;

// ── Header ────────────────────────────────────────────────────────────────────
const HEADER_H: f32 = 14.0; // mm
const HEADER_FONT_SIZE: f32 = 10.0; // pt

// ── Portrait column (left of Columns section) ─────────────────────────────────
const PORTRAIT_W: f32 = 40.0; // mm
const PORTRAIT_H: f32 = 50.0; // mm
const COL_GAP: f32 = 5.0; // mm between columns

// ── Text layout ───────────────────────────────────────────────────────────────
const LABEL_FONT_SIZE: f32 = 8.0; // pt
const VALUE_FONT_SIZE: f32 = 8.0; // pt
const LINE_H: f32 = 4.5; // mm per text row
const SECTION_PAD: f32 = 4.0; // mm gap between sections

// ── Error ─────────────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error)]
pub enum PdfRenderError {
    #[error("font error: {0}")]
    Font(String),
    #[error("image error: {0}")]
    Image(String),
    #[error("serialization error: {0}")]
    Serialization(String),
}

// ── Renderer ──────────────────────────────────────────────────────────────────

pub struct PdfRenderer;

impl PdfRenderer {
    /// Render any `PdfSource` to raw PDF bytes.
    pub fn render(source: &dyn PdfSource) -> Result<Vec<u8>, PdfRenderError> {
        let title = source.document_title();
        let theme = source.theme();
        let sections = source.sections();

        let (doc, page, layer_idx) =
            PdfDocument::new(&title, Mm(PAGE_W), Mm(PAGE_H), "Main");
        let layer = doc.get_page(page).get_layer(layer_idx);

        let font_bold = doc
            .add_builtin_font(BuiltinFont::HelveticaBold)
            .map_err(|e| PdfRenderError::Font(e.to_string()))?;
        let font_reg = doc
            .add_builtin_font(BuiltinFont::Helvetica)
            .map_err(|e| PdfRenderError::Font(e.to_string()))?;

        // y_cursor: top edge of the next section to render, in PDF coords
        // (origin = bottom-left, y increases upward)
        let mut y_cursor = PAGE_H;
        let ctx = RenderCtx {
            layer: &layer,
            font_bold: &font_bold,
            font_reg: &font_reg,
            theme: &theme,
        };

        for section in &sections {
            y_cursor = render_section(&ctx, section, y_cursor, MARGIN, CONTENT_W)?;
        }

        let mut buf = BufWriter::new(Vec::new());
        doc.save(&mut buf)
            .map_err(|e| PdfRenderError::Serialization(e.to_string()))?;
        buf.into_inner()
            .map_err(|e| PdfRenderError::Serialization(e.to_string()))
    }
}

// ── Section dispatch ──────────────────────────────────────────────────────────

/// Bundles the per-render immutable context to keep `render_section` under 7 args.
struct RenderCtx<'a> {
    layer: &'a PdfLayerReference,
    font_bold: &'a IndirectFontRef,
    font_reg: &'a IndirectFontRef,
    theme: &'a PdfTheme,
}

/// Render one section. Returns the new y_cursor (bottom edge of this section).
fn render_section(
    ctx: &RenderCtx<'_>,
    section: &PdfSection,
    y_cursor: f32,
    x: f32,
    width: f32,
) -> Result<f32, PdfRenderError> {
    match section {
        PdfSection::Header { title, subtitle } => draw_header(
            ctx.layer,
            ctx.font_bold,
            ctx.font_reg,
            title,
            subtitle.as_deref(),
            &ctx.theme.header_color,
            y_cursor,
        ),

        PdfSection::KeyValueList { title, entries } => draw_key_value_list(
            ctx.layer,
            ctx.font_bold,
            ctx.font_reg,
            title.as_deref(),
            entries,
            y_cursor,
            x,
        ),

        PdfSection::Image {
            data,
            content_type,
            label: _,
        } => {
            if content_type.contains("jpeg") || content_type.contains("jpg") {
                draw_jpeg_image(ctx.layer, data, y_cursor, x)
            } else {
                // Unsupported image type: skip but advance cursor by portrait height
                Ok(y_cursor - PORTRAIT_H)
            }
        }

        PdfSection::Columns { left, right } => {
            let left_width = PORTRAIT_W;
            let right_x = x + left_width + COL_GAP;
            let right_width = width - left_width - COL_GAP;

            let mut left_y = y_cursor;
            for s in left {
                left_y = render_section(ctx, s, left_y, x, left_width)?;
            }
            let mut right_y = y_cursor;
            for s in right {
                right_y = render_section(ctx, s, right_y, right_x, right_width)?;
            }
            // Cursor advances to the lower of the two column bottoms
            Ok(left_y.min(right_y))
        }

        PdfSection::Footer { text } => draw_footer(ctx.layer, ctx.font_reg, text),

        PdfSection::TextBlock { title, body } => draw_text_block(
            ctx.layer,
            ctx.font_bold,
            ctx.font_reg,
            title.as_deref(),
            body,
            y_cursor,
            x,
        ),

        // Phase 2: barcode rendering not yet implemented
        PdfSection::Barcode { .. } => Ok(y_cursor),
    }
}

// ── Drawing helpers ───────────────────────────────────────────────────────────

/// Draw the full-width colored header bar with white title and optional subtitle.
fn draw_header(
    layer: &PdfLayerReference,
    font_bold: &IndirectFontRef,
    font_reg: &IndirectFontRef,
    title: &str,
    subtitle: Option<&str>,
    header_color: &(f32, f32, f32),
    y_top: f32,
) -> Result<f32, PdfRenderError> {
    let (r, g, b) = *header_color;

    // Filled rectangle spanning the full page width
    layer.set_fill_color(Color::Rgb(Rgb::new(r, g, b, None)));
    layer.add_rect(
        Rect::new(Mm(0.0), Mm(y_top - HEADER_H), Mm(PAGE_W), Mm(y_top))
            .with_mode(PaintMode::Fill)
            .with_winding(WindingOrder::NonZero),
    );

    // Vertically center text in the bar
    // Rough approximation: 1 pt ≈ 0.353 mm, cap-height ≈ font_size * 0.7 * 0.353 mm
    let text_y = y_top - HEADER_H + (HEADER_H - HEADER_FONT_SIZE * 0.353 * 0.7) / 2.0;

    // White title on the left
    layer.set_fill_color(Color::Rgb(Rgb::new(1.0, 1.0, 1.0, None)));
    layer.use_text(title, HEADER_FONT_SIZE, Mm(MARGIN), Mm(text_y), font_bold);

    // Subtitle on the right (approximate right-alignment: ~2.2 mm per char at 10pt)
    if let Some(sub) = subtitle {
        let approx_text_w = sub.len() as f32 * 2.2;
        let sub_x = (PAGE_W - MARGIN - approx_text_w).max(MARGIN + 60.0);
        layer.use_text(sub, HEADER_FONT_SIZE, Mm(sub_x), Mm(text_y), font_reg);
    }

    Ok(y_top - HEADER_H)
}

/// Draw a list of (label, value) pairs with bold labels.
fn draw_key_value_list(
    layer: &PdfLayerReference,
    font_bold: &IndirectFontRef,
    font_reg: &IndirectFontRef,
    title: Option<&str>,
    entries: &[(String, String)],
    y_top: f32,
    x: f32,
) -> Result<f32, PdfRenderError> {
    let mut y = y_top - SECTION_PAD;

    layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));

    if let Some(t) = title {
        y -= LINE_H;
        layer.use_text(t, LABEL_FONT_SIZE + 1.0, Mm(x), Mm(y), font_bold);
        y -= 2.0;
    }

    for (label, value) in entries {
        y -= LINE_H;
        // Draw "Label: " in bold then value in regular on the same baseline
        layer.begin_text_section();
        layer.set_font(font_bold, LABEL_FONT_SIZE);
        layer.set_text_cursor(Mm(x), Mm(y));
        layer.write_text(label.as_str(), font_bold);
        layer.write_text(": ", font_bold);
        layer.set_font(font_reg, VALUE_FONT_SIZE);
        layer.write_text(value.as_str(), font_reg);
        layer.end_text_section();
    }

    Ok(y - 2.0)
}

/// Decode a JPEG and place it at (`x`, `y_top - PORTRAIT_H`) scaled to PORTRAIT_W × PORTRAIT_H.
fn draw_jpeg_image(
    layer: &PdfLayerReference,
    data: &[u8],
    y_top: f32,
    x: f32,
) -> Result<f32, PdfRenderError> {
    let mut decoder = jpeg_decoder::Decoder::new(std::io::Cursor::new(data));
    let pixels = decoder
        .decode()
        .map_err(|e| PdfRenderError::Image(e.to_string()))?;
    let info = decoder
        .info()
        .ok_or_else(|| PdfRenderError::Image("missing JPEG metadata".to_string()))?;

    let color_space = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => ColorSpace::Greyscale,
        _ => ColorSpace::Rgb,
    };

    let image_xobject = ImageXObject {
        width: Px(info.width as usize),
        height: Px(info.height as usize),
        color_space,
        bits_per_component: ColorBits::Bit8,
        interpolate: true,
        image_data: pixels,
        image_filter: None,
        smask: None,
        clipping_bbox: None,
    };

    // Scale so the image occupies exactly PORTRAIT_W × PORTRAIT_H mm at DPI 300
    const DPI: f32 = 300.0;
    let scale_x = PORTRAIT_W * DPI / (25.4 * info.width as f32);
    let scale_y = PORTRAIT_H * DPI / (25.4 * info.height as f32);

    Image::from(image_xobject).add_to_layer(
        layer.clone(),
        ImageTransform {
            translate_x: Some(Mm(x)),
            translate_y: Some(Mm(y_top - PORTRAIT_H)),
            rotate: None,
            scale_x: Some(scale_x),
            scale_y: Some(scale_y),
            dpi: Some(DPI),
        },
    );

    Ok(y_top - PORTRAIT_H)
}

/// Draw a small disclaimer or informational text block.
fn draw_text_block(
    layer: &PdfLayerReference,
    font_bold: &IndirectFontRef,
    font_reg: &IndirectFontRef,
    title: Option<&str>,
    body: &str,
    y_top: f32,
    x: f32,
) -> Result<f32, PdfRenderError> {
    let mut y = y_top - SECTION_PAD;

    layer.set_fill_color(Color::Rgb(Rgb::new(0.0, 0.0, 0.0, None)));

    if let Some(t) = title {
        y -= LINE_H;
        layer.use_text(t, LABEL_FONT_SIZE + 1.0, Mm(x), Mm(y), font_bold);
    }

    y -= LINE_H;
    layer.use_text(body, VALUE_FONT_SIZE, Mm(x), Mm(y), font_reg);

    Ok(y - 2.0)
}

/// Draw footer text pinned near the bottom of the page.
fn draw_footer(
    layer: &PdfLayerReference,
    font_reg: &IndirectFontRef,
    text: &str,
) -> Result<f32, PdfRenderError> {
    let footer_y = MARGIN;
    layer.set_fill_color(Color::Rgb(Rgb::new(0.5, 0.5, 0.5, None)));
    layer.use_text(text, 7.0, Mm(MARGIN), Mm(footer_y), font_reg);
    Ok(footer_y - LINE_H)
}

