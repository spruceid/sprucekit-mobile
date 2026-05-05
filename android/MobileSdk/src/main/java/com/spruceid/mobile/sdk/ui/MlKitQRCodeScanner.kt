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
 * Drop-in replacement for [QRCodeScanner] backed by Google ML Kit's barcode scanner.
 *
 * The default ZXing-based [QRCodeScanner] does not reliably read very dense QR
 * codes (V37+ at L-EC, ~150+ modules per side) that the offline SD-JWT VP
 * pipeline produces (deflate+base10-compressed VP tokens push QR density to
 * the upper bound of the spec). ML Kit's barcode detector has been measured
 * to read these reliably on the same Android hardware.
 *
 * Public API mirrors [QRCodeScanner] exactly so callers can swap by
 * importing this composable instead — see `ScanningType.QRCODE_HIGH_DENSITY`
 * in the Showcase `ScanningComponent` for an opt-in switch that keeps the
 * ZXing path intact for every other QR scanning surface in the app.
 *
 * Background visuals are reused from [QRCodeScannerBackground].
 */
@Composable
fun MlKitQRCodeScanner(
    title: String = "Scan QR Code",
    titleColor: Color = Color.Black,
    subtitle: String = "Please align within the guides",
    subtitleColor: Color = Color.Black,
    cancelButtonLabel: String = "Cancel",
    cancelButtonColor: Color = Color.Black,
    cancelButtonBorderColor: Color = Color.Gray,
    onRead: (content: String) -> Unit,
    isMatch: (content: String) -> Boolean = { _ -> true },
    onCancel: () -> Unit,
    hideCancelButton: Boolean = false,
    fontFamily: FontFamily = FontFamily.Default,
    guidesColor: Color = Color.Blue,
    guidesText: String = "Detecting...",
    readerColor: Color = Color.White,
    backgroundColor: Color = Color.White,
    backgroundOpacity: Float = 1f,
    instructions: String = "",
    instructionsDefaultColor: Color = Color.Gray,
) {
    val context = LocalContext.current

    // ML Kit barcode scanner restricted to QR codes — narrows the detector's
    // search space and keeps latency low compared to scanning all symbologies.
    val barcodeScanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
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
        // High-density QR (V40 / 177 modules) decodes much faster the more
        // pixels-per-module ML Kit gets per frame. At 1080p with a typical
        // ~60% frame fill that's ~3.7 px/module — sits on ML Kit's reliable
        // threshold and produces a 3–5 s scan time on Pixel 9 because most
        // frames don't quite cross the confidence bar. Bumping to 4K
        // (3840×2160) lifts that to ~13 px/module at the same fill ratio,
        // trading frame rate (per-frame processing roughly 4× heavier) for
        // per-frame decode success rate. Net empirical effect on Pixel 9:
        // first-glance scan instead of multi-second hunt.
        imageAnalysisTargetResolution = Size(3840, 2160),
        // The shared 2× zoom in `GenericCameraXScanner` is helpful for
        // small/distant symbols (ZXing path's main use case) but actively
        // hurts here: 70mm QR + close hold + 2× zoom either crops the
        // symbol out of frame or amplifies sensor noise per-module. Hold
        // the phone naturally at native FOV instead.
        zoomRatio = 1f,
        imageAnalyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            COORDINATE_SYSTEM_ORIGINAL,
            ContextCompat.getMainExecutor(context)
        ) { analyzerResult ->
            val barcodes = analyzerResult.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            // First QR with a `rawValue` we recognise wins. ML Kit can return
            // multiple results per frame; we stop at the first match so the
            // callback fires exactly once per scan event (consistent with the
            // ZXing analyzer's behaviour).
            for (barcode in barcodes) {
                val raw = barcode.rawValue ?: continue
                if (isMatch(raw)) {
                    onRead(raw)
                    return@MlKitAnalyzer
                }
            }
        },
        background = {
            QRCodeScannerBackground(
                guidesText = guidesText,
                guidesColor = guidesColor,
                readerColor = readerColor,
                backgroundColor = backgroundColor,
                backgroundOpacity = backgroundOpacity,
                instructions = instructions,
                instructionsDefaultColor = instructionsDefaultColor,
                fontFamily = fontFamily,
            )
        },
    )
}
