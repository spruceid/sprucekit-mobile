package com.spruceid.mobile.sdk.ui

import android.content.Context
import android.graphics.ImageFormat
import android.os.Build
import android.util.Range
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.pdf417.PDF417Reader
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.concurrent.Executors

@Composable
fun PDF417Scanner(
    title: String = "Scan PDF417 Bar Code",
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
    // Lock orientation to landscape for PDF417 scanning
    PDF417GenericScanner(
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
        imageAnalyzer = PDF417Analyzer(
            isMatch = isMatch,
            onQrCodeScanned = { result ->
                onRead(result)
            }),
        background = {
            PDF417ScannerBackground(
                guidesColor = guidesColor,
                readerColor = readerColor,
                backgroundOpacity = backgroundOpacity,
            )
        }
    )
}

@Composable
fun PDF417GenericScanner(
    title: String = "Scan QR Code",
    titleColor: Color = Color.White,
    subtitle: String = "Please align within the guides",
    subtitleColor: Color = Color.White,
    cancelButtonLabel: String = "Cancel",
    cancelButtonColor: Color = Color.White,
    cancelButtonBorderColor: Color = Color.Gray,
    onCancel: () -> Unit,
    hideCancelButton: Boolean = false,
    fontFamily: FontFamily = FontFamily.Default,
    imageAnalyzer: ImageAnalysis.Analyzer,
    background: @Composable () -> Unit
) {
    val context = LocalContext.current
    val cameraProviderFuture =
        remember {
            ProcessCameraProvider.getInstance(context)
        }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Wrap onCancel to stop camera before calling the original callback
    val handleCancel: () -> Unit = remember {
        {
            try {
                cameraProviderFuture.get()?.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onCancel()
        }
    }

    DisposableEffect(key1 = cameraProviderFuture) {
        onDispose {
            cameraProviderFuture.get()?.unbindAll()
        }
    }

    fun setupCamera(context: Context): PreviewView {
        val previewView = PreviewView(context)
        val preview =
            Preview.Builder()
                .setTargetFrameRate(Range(30, 60)) // Higher frame rate for faster scanning
                .setTargetRotation(Surface.ROTATION_0)
                .build()
        val selector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val imageAnalysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

        imageAnalysis.setAnalyzer(
            Executors.newSingleThreadExecutor(),
            imageAnalyzer
        )
        var cameraControl: CameraControl? = null
        try {
            cameraControl = cameraProviderFuture
                .get()
                .bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis,
                ).cameraControl
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            cameraControl?.setZoomRatio(1.3f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return previewView
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier.fillMaxSize(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    setupCamera(context = context)
                },
            )
            background()
            Box(
                Modifier.fillMaxSize()
            ) {
                Column(
                    Modifier
                        .align(alignment = Alignment.CenterEnd)
                        .rotate(90f)
                        .offset(y = (-80).dp)
                ) {
                    Text(
                        text = title,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = titleColor,
                    )
                    Text(
                        text = subtitle,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        color = subtitleColor,
                    )
                }

                if (!hideCancelButton) {
                    Column(
                        Modifier
                            .align(Alignment.CenterStart)
                            .rotate(90f)
                            .offset(y = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = handleCancel,
                            shape = RoundedCornerShape(100.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = cancelButtonColor,
                            ),
                            border = BorderStroke(1.dp, cancelButtonBorderColor),
                            modifier = Modifier
                                .width(300.dp)
                                .padding(bottom = 35.dp),
                        ) {
                            Text(
                                text = cancelButtonLabel,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                color = cancelButtonColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

class PDF417Analyzer(
    private val onQrCodeScanned: (String) -> Unit,
    private val isMatch: (content: String) -> Boolean = { _ -> true },
) : ImageAnalysis.Analyzer {

    private val supportedImageFormats = mutableListOf(ImageFormat.YUV_420_888)
    private var hasScanned = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            supportedImageFormats.addAll(listOf(ImageFormat.YUV_422_888, ImageFormat.YUV_444_888))
        }
    }

    override fun analyze(image: ImageProxy) {
        if (hasScanned) {
            image.close()
            return
        }

        if (image.format in supportedImageFormats) {
            val bytes = image.planes[0].buffer.toByteArray()

            val useROI = image.width >= 1280
            val cropWidth = if (useROI) (image.width * 0.8).toInt() else image.width
            val cropHeight = if (useROI) (image.height * 0.8).toInt() else image.height
            val left = if (useROI) (image.width - cropWidth) / 2 else 0
            val top = if (useROI) (image.height - cropHeight) / 2 else 0

            val source =
                PlanarYUVLuminanceSource(
                    bytes,
                    image.width,
                    image.height,
                    left,
                    top,
                    cropWidth,
                    cropHeight,
                    false,
                )

            val hints: MutableMap<DecodeHintType, Any?> = EnumMap(
                DecodeHintType::class.java
            )
            hints[DecodeHintType.TRY_HARDER] = true
            hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.PDF_417)
            hints[DecodeHintType.ALSO_INVERTED] = true
            hints[DecodeHintType.PURE_BARCODE] = false

            val reader = PDF417Reader()

            try {
                // Try HybridBinarizer first (better for high contrast)
                val binaryBmp1 = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decode(binaryBmp1, hints)
                if (isMatch(result.text)) {
                    hasScanned = true
                    onQrCodeScanned(result.text)
                }
            } catch (e1: Exception) {
                try {
                    // Fallback to GlobalHistogramBinarizer for dense/low-contrast codes
                    val binaryBmp2 = BinaryBitmap(GlobalHistogramBinarizer(source))
                    val result = reader.decode(binaryBmp2, hints)
                    if (isMatch(result.text)) {
                        hasScanned = true
                        onQrCodeScanned(result.text)
                    }
                } catch (e2: Exception) {
                    // Silent fail - continue to next frame
                }
            } finally {
                image.close()
            }
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(remaining()).also {
            get(it)
        }
    }
}

@Composable
fun PDF417ScannerBackground(
    guidesColor: Color = Color.White,
    readerColor: Color = Color.White,
    backgroundOpacity: Float = 0.5f,
) {
    var canvasSize by remember {
        mutableStateOf(Size(0f, 0f))
    }

    // Calculate scanning area dimensions for landscape PDF417 scanning
    val scanAreaHeight = canvasSize.height * .65f
    val scanAreaTop = (canvasSize.height - scanAreaHeight) / 2
    val scanAreaBottom = scanAreaTop + scanAreaHeight

    val infiniteTransition = rememberInfiniteTransition("Infinite PDF417 line transition remember")
    val offsetTop by infiniteTransition.animateFloat(
        initialValue = scanAreaTop,
        targetValue = scanAreaBottom,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        "PDF417 scan line animation",
    )

    return Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundOpacity))
            .drawWithContent {
                canvasSize = size
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Wide and short rectangle for landscape PDF417 scanning
                val width = canvasWidth * .35f
                val height = canvasHeight * .65f

                val left = (canvasWidth - width) / 2
                val top = (canvasHeight - height) / 2
                val right = left + width
                val bottom = top + height
                val cornerLength = 40f
                val cornerRadius = 40f
                drawContent()
                drawRect(Color(0x99000000))
                drawRoundRect(
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    color = Color.Transparent,
                    blendMode = BlendMode.SrcIn,
                    cornerRadius = CornerRadius(cornerRadius - 10f),
                )
                drawRect(
                    topLeft = Offset(left, offsetTop),
                    size = Size(width, 2f),
                    color = readerColor,
                    style = Stroke(2.dp.toPx()),
                )

                val path = Path()

                // top left
                path.moveTo(left, (top + cornerRadius))
                path.arcTo(
                    Rect(
                        left = left,
                        top = top,
                        right = left + cornerRadius,
                        bottom = top + cornerRadius,
                    ),
                    180f,
                    90f,
                    true,
                )
                path.moveTo(left + (cornerRadius / 2f), top)
                path.lineTo(left + (cornerRadius / 2f) + cornerLength, top)
                path.moveTo(left, top + (cornerRadius / 2f))
                path.lineTo(left, top + (cornerRadius / 2f) + cornerLength)

                // top right
                path.moveTo(right - cornerRadius, top)
                path.arcTo(
                    Rect(
                        left = right - cornerRadius,
                        top = top,
                        right = right,
                        bottom = top + cornerRadius,
                    ),
                    270f,
                    90f,
                    true,
                )
                path.moveTo(right - (cornerRadius / 2f), top)
                path.lineTo(right - (cornerRadius / 2f) - cornerLength, top)
                path.moveTo(right, top + (cornerRadius / 2f))
                path.lineTo(right, top + (cornerRadius / 2f) + cornerLength)

                // bottom left
                path.moveTo(left, bottom - cornerRadius)
                path.arcTo(
                    Rect(
                        left = left,
                        top = bottom - cornerRadius,
                        right = left + cornerRadius,
                        bottom = bottom,
                    ),
                    90f,
                    90f,
                    true,
                )
                path.moveTo(left + (cornerRadius / 2f), bottom)
                path.lineTo(left + (cornerRadius / 2f) + cornerLength, bottom)
                path.moveTo(left, bottom - (cornerRadius / 2f))
                path.lineTo(left, bottom - (cornerRadius / 2f) - cornerLength)

                // bottom right
                path.moveTo(left, bottom - cornerRadius)
                path.arcTo(
                    Rect(
                        left = right - cornerRadius,
                        top = bottom - cornerRadius,
                        right = right,
                        bottom = bottom,
                    ),
                    0f,
                    90f,
                    true,
                )
                path.moveTo(right - (cornerRadius / 2f), bottom)
                path.lineTo(right - (cornerRadius / 2f) - cornerLength, bottom)
                path.moveTo(right, bottom - (cornerRadius / 2f))
                path.lineTo(right, bottom - (cornerRadius / 2f) - cornerLength)

                drawPath(
                    path,
                    color = guidesColor,
                    style = Stroke(width = 15f),
                )
            },
    )
}