package com.spruceid.mobilesdkexample.wallet

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// The scheme for the OID4VP QR code.
const val OID4VP_SCHEME = "openid4vp://"
const val MDOC_OID4VP_SCHEME = "mdoc-openid4vp://"

// The scheme for the OID4VCI QR code.
const val OID4VCI_SCHEME = "openid-credential-offer://"

// The schemes for HTTP/HTTPS QR code.
const val HTTP_SCHEME = "http://"
const val HTTPS_SCHEME = "https://"

enum class SupportedQRTypes {
    OID4VP,
    OID4VCI_AND_HTTP,
    ALL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DispatchQRView(
    navController: NavController,
    credentialPackId: String?,
    supportedTypes: SupportedQRTypes = SupportedQRTypes.ALL
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun back() {
        navController.navigate(
            Screen.HomeScreen.route.replace("{tab}", "wallet")
        ) {
            popUpTo(0)
        }
    }

    fun onRead(payload: String) {
        loading = true
        scope.launch {
            try {
                val encodedUrl = URLEncoder.encode(payload, StandardCharsets.UTF_8.toString())

                when (supportedTypes) {
                    SupportedQRTypes.OID4VP -> {
                        if (payload.startsWith(OID4VP_SCHEME)) {
                            val baseUrl = "oid4vp/$encodedUrl"
                            val route = if (!credentialPackId.isNullOrEmpty()) {
                                "$baseUrl?credential_pack_id=$credentialPackId"
                            } else baseUrl
                            navController.navigate(route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else if (payload.startsWith(MDOC_OID4VP_SCHEME)) {
                            val baseUrl = "mdoc_oid4vp/$encodedUrl"
                            val route = if (!credentialPackId.isNullOrEmpty()) {
                                "$baseUrl?credential_pack_id=$credentialPackId"
                            } else baseUrl
                            navController.navigate(route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else {
                            err = "Unsupported QR code for OID4VP. Payload: $payload"
                        }
                    }

                    SupportedQRTypes.OID4VCI_AND_HTTP -> {
                        if (payload.startsWith(OID4VCI_SCHEME)) {
                            navController.navigate("oid4vci/$encodedUrl") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else if (payload.startsWith(HTTP_SCHEME) || payload.startsWith(
                                HTTPS_SCHEME
                            )
                        ) {
                            uriHandler.openUri(payload)
                            back()
                        } else {
                            err = "Unsupported QR code for OID4VCI/HTTP. Payload: $payload"
                        }
                    }

                    SupportedQRTypes.ALL -> {
                        when {
                            payload.startsWith(OID4VP_SCHEME) -> {
                                val baseUrl = "oid4vp/$encodedUrl"
                                val route = if (!credentialPackId.isNullOrEmpty()) {
                                    "$baseUrl?credential_pack_id=$credentialPackId"
                                } else baseUrl
                                navController.navigate(route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }

                            payload.startsWith(MDOC_OID4VP_SCHEME) -> {
                                val baseUrl = "mdoc_oid4vp/$encodedUrl"
                                val route = if (!credentialPackId.isNullOrEmpty()) {
                                    "$baseUrl?credential_pack_id=$credentialPackId"
                                } else baseUrl
                                navController.navigate(route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }

                            payload.startsWith(OID4VCI_SCHEME) -> {
                                navController.navigate("oid4vci/$encodedUrl") {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }

                            payload.startsWith(HTTP_SCHEME) || payload.startsWith(HTTPS_SCHEME) -> {
                                uriHandler.openUri(payload)
                                back()
                            }

                            else -> {
                                err =
                                    "The QR code you have scanned is not supported. Payload: $payload"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                err = e.localizedMessage
            }
        }
    }

    if (err != null) {
        ErrorView(
            errorTitle = "Error Reading QR Code",
            errorDetails = err!!,
            onClose = ::back
        )
    } else if (loading) {
        LoadingView(loadingText = "Loading...")
    } else {
        ScanningComponent(
            scanningType = ScanningType.QRCODE,
            onRead = ::onRead,
            onCancel = ::back
        )
    }

}
