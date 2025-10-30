package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.IndividualCredentialScanningComponent
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
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
    OID4VCI,
    HTTP
}

val ALL_SUPPORTED_QR_TYPES =
    listOf(SupportedQRTypes.OID4VP, SupportedQRTypes.OID4VCI, SupportedQRTypes.HTTP)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DispatchQRView(
    navController: NavController,
    credentialPackId: String? = null,
    supportedTypes: List<SupportedQRTypes> = ALL_SUPPORTED_QR_TYPES,
    backgroundColor: Color = Color.White,
    hideCancelButton: Boolean = false,
    isInsideCredentialDetails: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var err by remember { mutableStateOf<String?>(null) }
    var showOID4VPModal by remember { mutableStateOf(false) }
    var modalPayload by remember { mutableStateOf<String?>(null) }

    fun back() {
        navController.navigate(
            Screen.HomeScreen.route.replace("{tab}", "wallet")
        ) {
            popUpTo(0)
        }
    }

    fun onRead(payload: String) {
        scope.launch {
            try {
                val encodedUrl = URLEncoder.encode(payload, StandardCharsets.UTF_8.toString())

                // Check payload type
                val qrType = when {
                    payload.startsWith(OID4VP_SCHEME) -> SupportedQRTypes.OID4VP
                    payload.startsWith(MDOC_OID4VP_SCHEME) -> SupportedQRTypes.OID4VP
                    payload.startsWith(OID4VCI_SCHEME) -> SupportedQRTypes.OID4VCI
                    payload.startsWith(HTTP_SCHEME) || payload.startsWith(HTTPS_SCHEME) -> SupportedQRTypes.HTTP
                    else -> null
                }

                // Check if payload type is supported
                if (qrType != null && supportedTypes.contains(qrType)) {
                    when (qrType) {
                        SupportedQRTypes.OID4VP -> {
                            if (isInsideCredentialDetails) {
                                // Show modal instead of navigating
                                modalPayload = payload
                                showOID4VPModal = true
                            } else {
                                // Navigate to full screen
                                val baseRoute = when {
                                    payload.startsWith(OID4VP_SCHEME) && !credentialPackId.isNullOrEmpty() ->
                                        Screen.HandleOID4VPWithCredentialPack.route.replace(
                                            "{credential_pack_id}",
                                            credentialPackId
                                        )

                                    payload.startsWith(OID4VP_SCHEME) ->
                                        Screen.HandleOID4VP.route

                                    payload.startsWith(MDOC_OID4VP_SCHEME) && !credentialPackId.isNullOrEmpty() ->
                                        Screen.HandleMdocOID4VPWithCredentialPack.route.replace(
                                            "{credential_pack_id}",
                                            credentialPackId
                                        )

                                    payload.startsWith(MDOC_OID4VP_SCHEME) ->
                                        Screen.HandleMdocOID4VP.route

                                    else -> throw IllegalArgumentException("Invalid OID4VP scheme")
                                }

                                val route = baseRoute.replace("{url}", encodedUrl)

                                navController.navigate(route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }

                        SupportedQRTypes.OID4VCI -> {
                            navController.navigate("oid4vci/$encodedUrl") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        }

                        SupportedQRTypes.HTTP -> {
                            uriHandler.openUri(payload)
                            back()
                        }
                    }
                } else {
                    err = "Unsupported QR code type. Payload: $payload"
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
    } else {
        if (isInsideCredentialDetails) {
            IndividualCredentialScanningComponent(
                backgroundColor = backgroundColor,
                onRead = ::onRead,
                onCancel = ::back
            )
        } else {
            ScanningComponent(
                scanningType = ScanningType.QRCODE,
                backgroundColor = backgroundColor,
                hideCancelButton = hideCancelButton,
                onRead = ::onRead,
                onCancel = ::back
            )
        }
    }

    // Show OID4VP modal when inside credential details
    if (showOID4VPModal && modalPayload != null) {
        OID4VPModal(
            payload = modalPayload!!,
            credentialPackId = credentialPackId,
            navController = navController,
            onClose = {
                showOID4VPModal = false
                modalPayload = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OID4VPModal(
    payload: String,
    credentialPackId: String?,
    navController: NavController,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = ColorBase50,
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            when {
                payload.startsWith(OID4VP_SCHEME) -> {
                    HandleOID4VPView(
                        navController = navController,
                        url = payload,
                        credentialPackId = credentialPackId
                    )
                }

                payload.startsWith(MDOC_OID4VP_SCHEME) -> {
                    HandleMdocOID4VPView(
                        navController = navController,
                        url = payload,
                        credentialPackId = credentialPackId
                    )
                }
            }
        }
    }
}
