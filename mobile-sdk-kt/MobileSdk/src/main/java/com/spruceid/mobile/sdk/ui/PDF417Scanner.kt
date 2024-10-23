package com.spruceid.mobile.sdk.ui

import android.graphics.ImageFormat
import android.os.Build
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.pdf417.PDF417Reader
import java.nio.ByteBuffer
import java.util.EnumMap

@Composable
fun PDF417Scanner(
    title: String = "Scan QR Code",
    subtitle: String = "Please align within the guides",
    cancelButtonLabel: String = "Cancel",
    onRead: (content: String) -> Unit,
    isMatch: (content: String) -> Boolean = {_ -> true},
    onCancel: () -> Unit,
    fontFamily: FontFamily = FontFamily.Default,
    guidesColor: Color = Color.White,
    readerColor: Color = Color.White,
    textColor: Color = Color.White,
    backgroundOpacity: Float = 0.5f,
) {

    GenericCameraXScanner(
        title = title,
        subtitle = subtitle,
        cancelButtonLabel = cancelButtonLabel,
        onCancel = onCancel,
        fontFamily = fontFamily,
        textColor = textColor,
        imageAnalyzer =  PDF417Analyzer(
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

class PDF417Analyzer(
    private val onQrCodeScanned: (String) -> Unit,
    private val isMatch: (content: String) -> Boolean = {_ -> true},
) : ImageAnalysis.Analyzer {

    private val supportedImageFormats = mutableListOf(ImageFormat.YUV_420_888)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            supportedImageFormats.addAll(listOf(ImageFormat.YUV_422_888, ImageFormat.YUV_444_888))
        }
    }

    override fun analyze(image: ImageProxy) {
        if (image.format in supportedImageFormats) {
            val bytes = image.planes[0].buffer.toByteArray()
            val source =
                PlanarYUVLuminanceSource(
                    bytes,
                    image.width,
                    image.height,
                    0,
                    0,
                    image.width,
                    image.height,
                    false,
                )
            val binaryBmp = BinaryBitmap(HybridBinarizer(source))

            val hints: MutableMap<DecodeHintType, Any?> = EnumMap(
                DecodeHintType::class.java
            )

            hints[DecodeHintType.TRY_HARDER] = true

            try {
                val result = PDF417Reader().decode(binaryBmp, hints)
                if (isMatch(result.text)) {
                    onQrCodeScanned(result.text)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
    val infiniteTransition = rememberInfiniteTransition("Infinite QR code line transition remember")
    val offsetTop by infiniteTransition.animateFloat(
        initialValue = canvasSize.height * .35f,
        targetValue = canvasSize.height * .35f + canvasSize.width * .6f,
        animationSpec =
        infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        "QR code line animation",
    )

    return Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundOpacity))
            .drawWithContent {
                canvasSize = size
                val canvasWidth = size.width
                val canvasHeight = size.height
                val width = canvasWidth * .6f
                val height = canvasHeight * .6f


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