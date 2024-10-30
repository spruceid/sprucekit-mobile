package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.rs.DelegatedVerifier
import com.spruceid.mobile.sdk.rs.DelegatedVerifierStatus
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.db.VerificationMethods
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.rememberQrBitmapPainter
import com.spruceid.mobilesdkexample.ui.theme.BorderSecondary
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import io.ktor.http.Url
import kotlinx.coroutines.launch

enum class VerifyDelegatedOid4vpViewSteps {
    LOADING_QRCODE,
    PRESENTING_QRCODE,
    GETTING_STATUS,
    DISPLAYING_CREDENTIAL
}

@Composable
fun VerifyDelegatedOid4vpView(
        navController: NavController,
        verificationId: String,
        verificationMethodsViewModel: VerificationMethodsViewModel
) {
    val scope = rememberCoroutineScope()

    lateinit var verificationMethod: VerificationMethods
    lateinit var url: Url
    lateinit var baseUrl: String

    var step by remember { mutableStateOf(VerifyDelegatedOid4vpViewSteps.LOADING_QRCODE) }
    var status by remember { mutableStateOf(DelegatedVerifierStatus.INITIATED) }
    var loading by remember { mutableStateOf<String?>(null) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorDescription by remember { mutableStateOf<String?>(null) }

    lateinit var verifier: DelegatedVerifier
    var authQuery by remember { mutableStateOf<String?>(null) }
    lateinit var uri: String
    var presentation by remember { mutableStateOf<String?>(null) }

    fun monitorStatus(status: DelegatedVerifierStatus) {
        scope.launch {
            val res =
                    verifier.pollVerificationStatus("$uri?status=${status.toString().lowercase()}")
            when (res.status) {
                DelegatedVerifierStatus.INITIATED -> monitorStatus(res.status)
                DelegatedVerifierStatus.PENDING -> {
                    // display loading view
                    // call next status monitor
                }
                DelegatedVerifierStatus.FAILED -> {
                    // display error view
                }
                DelegatedVerifierStatus.SUCCESS -> {
                    // display credential
                    // res.presentation
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            // Verification method from db
            verificationMethod =
                    verificationMethodsViewModel.getVerificationMethod(verificationId.toLong())

            // Verification method base url
            url = Url(verificationMethod.url)

            baseUrl = "${url.protocol.name}://${url.host}:${url.port}"

            // Delegated Verifier
            verifier = DelegatedVerifier.newClient(baseUrl)

            // Get initial parameters to delegate verification
            val delegatedInitializationResponse =
                    verifier.requestDelegatedVerification(url.encodedPathAndQuery)
            authQuery = "openid4vp://?${delegatedInitializationResponse.authQuery}"

            uri = delegatedInitializationResponse.uri

            // Display QR Code
            step = VerifyDelegatedOid4vpViewSteps.PRESENTING_QRCODE

            // Call method to start monitoring status
            monitorStatus(status)
        } catch (e: Exception) {
            errorTitle = "Failed getting QR Code"
            errorDescription = e.localizedMessage
        }
    }

    fun back() {
        navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
    }

    if (errorTitle != null && errorDescription != null) {
        ErrorView(
                errorTitle = errorTitle!!,
                errorDetails = errorDescription!!,
                onClose = { back() }
        )
    } else {
        when (step) {
            VerifyDelegatedOid4vpViewSteps.LOADING_QRCODE -> {
                LoadingView(
                        loadingText = "Getting QR Code",
                        cancelButtonLabel = "Cancel",
                        onCancel = { back() }
                )
            }
            VerifyDelegatedOid4vpViewSteps.PRESENTING_QRCODE -> {
                if (authQuery != null) {
                    DelegatedVerifierDisplayQRCodeView(payload = authQuery!!, onClose = { back() })
                }
            }
            VerifyDelegatedOid4vpViewSteps.GETTING_STATUS -> {
                LoadingView(
                        loadingText = "Verifying status - $status",
                        cancelButtonLabel = "Cancel",
                        onCancel = { back() }
                )
            }
            VerifyDelegatedOid4vpViewSteps.DISPLAYING_CREDENTIAL -> {
                Text("presentation")
            }
        }
    }
}

@Composable
fun DelegatedVerifierDisplayQRCodeView(payload: String, onClose: () -> Unit) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(top = 60.dp)
                            .padding(bottom = 40.dp)
                            .padding(horizontal = 30.dp)
                            .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
                painter = rememberQrBitmapPainter(payload, size = 300.dp),
                contentDescription =
                        stringResource(
                                id = com.spruceid.mobilesdkexample.R.string.delegated_oid4vp_qrcode
                        ),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Button(
                onClick = { onClose() },
                shape = RoundedCornerShape(6.dp),
                colors =
                        ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ColorStone950,
                        ),
                modifier =
                        Modifier.fillMaxWidth()
                                .border(
                                        width = 1.dp,
                                        color = BorderSecondary,
                                        shape = RoundedCornerShape(6.dp)
                                )
        ) {
            Text(
                    text = "Cancel",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorStone950,
            )
        }
    }
}
