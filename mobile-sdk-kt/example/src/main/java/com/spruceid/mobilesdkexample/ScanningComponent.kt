package com.spruceid.mobilesdkexample

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.spruceid.mobile.sdk.ui.MRZScanner
import com.spruceid.mobile.sdk.ui.PDF417Scanner
import com.spruceid.mobile.sdk.ui.QRCodeScanner
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.Primary

enum class ScanningType {
    QRCODE, PDF417, MRZ
}

@ExperimentalMaterial3Api
@ExperimentalPermissionsApi
@Composable
fun ScanningComponent(
    navController: NavController,
    scanningType: ScanningType,
    title: String = "Scan QR Code",
    subtitle: String = "Please align within the guides",
    onRead: (content: String) -> Unit,
    isMatch: (content: String) -> Boolean = { _ -> true },
    onCancel: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val permissionsState =
        rememberMultiplePermissionsState(
            permissions =
            listOf(
                Manifest.permission.CAMERA,
            ),
        )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(
        key1 = lifecycleOwner,
        effect = {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        },
    )

    fun backHome() {
        navController.popBackStack()
    }

    fun internalOnCancel() {
        if (onCancel != null) {
            onCancel()
        } else {
            backHome()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        val permissionNotGranted =
            permissionsState.permissions.filter { perm -> !perm.status.isGranted }
        if (permissionNotGranted.isEmpty()) {
            when (scanningType) {
                ScanningType.QRCODE -> QRCodeScanner(
                    title = title,
                    subtitle = subtitle,
                    cancelButtonLabel = "Cancel",
                    onRead = onRead,
                    isMatch = isMatch,
                    onCancel = ::internalOnCancel,
                    fontFamily = Inter,
                    readerColor = Color.White,
                    guidesColor = Color.White,
                    textColor = Color.White,
                    backgroundOpacity = 0.5f
                )

                ScanningType.PDF417 -> PDF417Scanner(
                    title = title,
                    subtitle = subtitle,
                    cancelButtonLabel = "Cancel",
                    onRead = onRead,
                    isMatch = isMatch,
                    onCancel = ::internalOnCancel,
                    fontFamily = Inter,
                    readerColor = Color.White,
                    guidesColor = Color.White,
                    textColor = Color.White,
                    backgroundOpacity = 0.5f
                )

                ScanningType.MRZ -> MRZScanner(
                    title = title,
                    subtitle = subtitle,
                    cancelButtonLabel = "Cancel",
                    onRead = onRead,
                    isMatch = isMatch,
                    onCancel = ::internalOnCancel,
                    fontFamily = Inter,
                    readerColor = Color.White,
                    guidesColor = Color.White,
                    textColor = Color.White,
                    backgroundOpacity = 0.5f
                )
            }
        } else {
            AlertDialog(
                containerColor = Color.White,
                shape = RoundedCornerShape(8.dp),
                onDismissRequest = {
                    backHome()
                },
                title = {
                    Text(
                        "Camera permission denied",
                        fontFamily = Inter,
                    )
                },
                text = {
                    Text(
                        "Please provide the permissions for scanning QR code",
                        fontFamily = Inter,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // send to app settings if permission is denied permanently
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            context.startActivity(intent)
                        },
                        colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Color.White,
                        ),
                        modifier =
                        Modifier
                            .padding(vertical = 2.dp)
                            .border(2.dp, Color.Transparent, RoundedCornerShape(100.dp)),
                    ) {
                        Text(
                            fontFamily = Inter,
                            text = "Go to Settings",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color.White,
                        )
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            backHome()
                        },
                        colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Primary,
                        ),
                    ) {
                        Text(
                            text = "Cancel",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Primary,
                        )
                    }
                },
            )
        }
    }
}