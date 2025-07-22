package com.spruceid.mobile.sdk.ui

import android.content.Context
import android.util.Range
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun GenericCameraXScanner(
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

    DisposableEffect(key1 = cameraProviderFuture) {
        onDispose {
            cameraProviderFuture.get()?.unbindAll()
        }
    }

    fun setupCamera(context: Context): PreviewView {
        val previewView = PreviewView(context)
        val preview =
            Preview.Builder()
                .setTargetFrameRate(Range(20, 45))
                .setTargetRotation(Surface.ROTATION_0)
                .build()
        val selector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val imageAnalysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .build()
        imageAnalysis.setAnalyzer(
            ContextCompat.getMainExecutor(context),
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
            cameraControl?.setZoomRatio(2f)
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
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp)
                        .padding(horizontal = 30.dp),
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
                        Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = onCancel,
                            shape = RoundedCornerShape(100.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = cancelButtonColor,
                            ),
                            border = BorderStroke(1.dp, cancelButtonBorderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 50.dp)
                                .padding(horizontal = 30.dp),
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