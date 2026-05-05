package com.spruceid.mobile.sdk.ui

import android.util.Size
import androidx.camera.core.ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Drop-in replacement for [PDF417Scanner] backed by Google ML Kit's
 * barcode scanner.
 *
 * The default ZXing-based [PDF417Scanner] does not reliably read dense
 * PDF-417 codes (e.g. real AAMVA driver-license payloads encode ~340 bytes
 * → ~30 columns × ~30 rows, with each module rendered at <50µm in a
 * compact PDF slot).  ML Kit's barcode detector — the same engine Google
 * Lens uses — reads these reliably on the same Android hardware.
 *
 * Public API mirrors [PDF417Scanner] exactly so callers can swap by
 * importing this composable instead — see `ScannerType.pdf417HighDensity`
 * in the Flutter plugin and `ScanningType.PDF417_HIGH_DENSITY` (when
 * added) in the Showcase `ScanningComponent` for an opt-in switch that
 * keeps the ZXing path intact for every other PDF-417 scanning surface.
 *
 * Background visuals are reused from [PDF417ScannerBackground].
 */
@Composable
fun MlKitPDF417Scanner(
    title: String = "Scan PDF-417",
    titleColor: Color = Color.White,
    subtitle: String = "Please align within the guides",
    subtitleColor: Color = Color.White,
    cancelButtonLabel: String = "Cancel",
    cancelButtonColor: Color = Color.White,
    cancelButtonBorderColor: Color = Color.White,
    hideCancelButton: Boolean = false,
    onRead: (content: String) -> Unit,
    isMatch: (content: String) -> Boolean = { _ -> true },
    onCancel: () -> Unit,
    fontFamily: FontFamily = FontFamily.Default,
    guidesColor: Color = Color.White,
    readerColor: Color = Color.White,
    backgroundOpacity: Float = 0.5f,
) {
    val context = LocalContext.current

    // ML Kit barcode scanner restricted to PDF-417 — narrows the
    // detector's search space and keeps latency low.
    val barcodeScanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                .build()
        )
    }

    GenericCameraXScanner(
        title = title,
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        cancelButtonLabel = cancelButtonLabel,
        cancelButtonColor = cancelButtonColor,
        cancelButtonBorderColor = cancelButtonBorderColor,
        onCancel = onCancel,
        hideCancelButton = hideCancelButton,
        fontFamily = fontFamily,
        // Same 4K rationale as MlKitQRCodeScanner: dense PDF-417 needs
        // pixels-per-module headroom to cross ML Kit's confidence bar.
        // Devices that can't supply 4K analysis fall back to the closest
        // available higher / lower resolution via GenericCameraXScanner's
        // ResolutionStrategy — no crash, just less margin.
        imageAnalysisTargetResolution = Size(3840, 2160),
        // Disable the shared 2× zoom — PDF-417 on the back of a credential
        // PDF is held close-up; zoom either crops the symbol out of frame
        // or amplifies sensor noise per-module.
        zoomRatio = 1f,
        imageAnalyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            COORDINATE_SYSTEM_ORIGINAL,
            ContextCompat.getMainExecutor(context)
        ) { analyzerResult ->
            val barcodes = analyzerResult.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            // First PDF-417 with a `rawValue` we recognise wins.
            for (barcode in barcodes) {
                val raw = barcode.rawValue ?: continue
                if (isMatch(raw)) {
                    onRead(raw)
                    return@MlKitAnalyzer
                }
            }
        },
        background = {
            PDF417ScannerBackground(
                guidesColor = guidesColor,
                readerColor = readerColor,
                backgroundOpacity = backgroundOpacity,
            )
        },
    )
}
