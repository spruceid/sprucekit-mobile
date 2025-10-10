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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.EnumMap

@Composable
fun QRCodeScanner(
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
    instructionsDefaultColor: Color = Color.Gray
) {

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
        imageAnalyzer = QrCodeAnalyzer(
            isMatch = isMatch,
            onQrCodeScanned = { result ->
                onRead(result)
            }),
        background = {
            QRCodeScannerBackground(
                guidesText = guidesText,
                guidesColor = guidesColor,
                readerColor = readerColor,
                backgroundColor = backgroundColor,
                backgroundOpacity = backgroundOpacity,
                instructions = instructions,
                instructionsDefaultColor = instructionsDefaultColor,
                fontFamily = fontFamily
            )
        }
    )
}

@Composable
fun QRCodeScannerBackground(
    guidesText: String = "Detecting",
    guidesColor: Color = Color.Blue,
    readerColor: Color = Color.White,
    backgroundColor: Color = Color.White,
    backgroundOpacity: Float = 1f,
    instructions: String = "",
    instructionsDefaultColor: Color = Color.Gray,
    fontFamily: FontFamily = FontFamily.Default
) {
    val canvasSizeHeightOffsetMultiplier = .25f
    val canvasSizeWidthOffsetMultiplier = .6f

    var canvasSize by remember {
        mutableStateOf(Size(0f, 0f))
    }
    var guidesMessageOffsetTop by remember {
        mutableFloatStateOf(0f)
    }
    var bluePillHeight by remember {
        mutableFloatStateOf(0f)
    }
    val infiniteTransition = rememberInfiniteTransition("Infinite QR code line transition remember")
    val offsetTop by infiniteTransition.animateFloat(
        initialValue = canvasSize.height * canvasSizeHeightOffsetMultiplier,
        targetValue = canvasSize.height * canvasSizeHeightOffsetMultiplier + canvasSize.width * canvasSizeWidthOffsetMultiplier,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        "QR code line animation",
    )

    return Box(
        Modifier.fillMaxSize()
    ) {
        // Background
        Box(
            Modifier
                .fillMaxSize()
                .background(backgroundColor.copy(alpha = backgroundOpacity))
                .drawWithContent {
                    canvasSize = size
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val width = canvasWidth * canvasSizeWidthOffsetMultiplier

                    val left = (canvasWidth - width) / 2
                    val top = canvasHeight * canvasSizeHeightOffsetMultiplier
                    val cornerRadius = 20f
                    guidesMessageOffsetTop = top + width

                    drawContent()
                    drawRoundRect(
                        topLeft = Offset(left, top),
                        size = Size(width, width),
                        color = Color.Transparent,
                        blendMode = BlendMode.SrcIn,
                        cornerRadius = CornerRadius(cornerRadius),
                    )
                    drawRect(
                        topLeft = Offset(left, offsetTop),
                        size = Size(width, 2f),
                        color = readerColor,
                        style = Stroke(2.dp.toPx()),
                    )
                    // Draw rectangle border
                    drawRoundRect(
                        topLeft = Offset(left, top),
                        size = Size(width, width),
                        color = guidesColor,
                        style = Stroke(3.dp.toPx()),
                        cornerRadius = CornerRadius(cornerRadius),
                    )
                }
        )

        // Blue pill component with loader and text
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(
                        0,
                        (guidesMessageOffsetTop - (bluePillHeight / 2) - 10.dp.toPx()).toInt() // offset - height - padding
                    )
                }
                .background(
                    color = guidesColor,
                    shape = RoundedCornerShape(100f)
                )
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .onGloballyPositioned { coordinates ->
                    bluePillHeight = coordinates.size.height.toFloat()
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Rotating loader
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.Gray.copy(alpha = 0.6f),
                        trackColor = Color.White,
                        strokeWidth = 2.dp
                    )
                }

                // Text
                Text(
                    text = guidesText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        // Instructions text
        Text(
            text = instructions,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp)
                .offset {
                    IntOffset(
                        0,
                        (guidesMessageOffsetTop + bluePillHeight + 10.dp.toPx()).toInt() // offset + height + padding
                    )
                },
            color = instructionsDefaultColor,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}

class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit,
    private val isMatch: (content: String) -> Boolean = { _ -> true },
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
                val result = QRCodeReader().decode(binaryBmp, hints)
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
fun MinimalQRCodeScanner(
    onRead: (content: String) -> Unit,
    isMatch: (content: String) -> Boolean = { _ -> true },
    onCancel: () -> Unit,
    backgroundColor: Color,
    borderColor: Color = Color.Blue,
    instructionsText: String = "Scan the provided verification QR Code in order to share data",
    instructionsColor: Color = Color.Gray,
    fontFamily: FontFamily = FontFamily.Default
) {
    GenericCameraXScanner(
        title = "",
        titleColor = Color.Transparent,
        subtitle = "",
        subtitleColor = Color.Transparent,
        cancelButtonLabel = "",
        onCancel = onCancel,
        hideCancelButton = true,
        fontFamily = fontFamily,
        imageAnalyzer = QrCodeAnalyzer(
            isMatch = isMatch,
            onQrCodeScanned = { result ->
                onRead(result)
            }),
        background = {
            MinimalQRScannerBackground(
                backgroundColor = backgroundColor,
                borderColor = borderColor,
                instructionsText = instructionsText,
                instructionsColor = instructionsColor,
                fontFamily = fontFamily
            )
        }
    )
}

@Composable
fun MinimalQRScannerBackground(
    backgroundColor: Color = Color.White,
    borderColor: Color = Color.Blue,
    instructionsText: String = "Scan the provided verification QR Code in order to share data",
    instructionsColor: Color = Color.Gray,
    fontFamily: FontFamily = FontFamily.Default
) {
    val canvasSizeHeightOffsetMultiplier = .08f
    val canvasSizeWidthOffsetMultiplier = .7f

    Box(
        Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .drawWithContent {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val width = canvasWidth * canvasSizeWidthOffsetMultiplier

                    val left = (canvasWidth - width) / 2
                    val top = canvasHeight * canvasSizeHeightOffsetMultiplier
                    val cornerRadius = 12f

                    drawContent()

                    drawRect(
                        color = backgroundColor,
                        size = size
                    )

                    drawRoundRect(
                        topLeft = Offset(left, top),
                        size = Size(width, width),
                        color = Color.Transparent,
                        blendMode = BlendMode.SrcIn,
                        cornerRadius = CornerRadius(cornerRadius),
                    )

                    drawRoundRect(
                        topLeft = Offset(left, top),
                        size = Size(width, width),
                        color = borderColor,
                        style = Stroke(2.dp.toPx()),
                        cornerRadius = CornerRadius(cornerRadius),
                    )
                }
        )
        if (instructionsText.isNotEmpty()) {
            Text(
                text = instructionsText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp)
                    .padding(top = 80.dp),
                color = instructionsColor,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
            )
        }
    }
}