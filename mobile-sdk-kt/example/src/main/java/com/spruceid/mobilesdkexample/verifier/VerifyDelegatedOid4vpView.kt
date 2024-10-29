package com.spruceid.mobilesdkexample.verifier

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.rs.DelegatedVerifier
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.navigation.Screen

enum class VerifyDelegatedOid4vpViewStatus {
    INITIATED, PENDING, SUCCESS, FAILED
}

enum class VerifyDelegatedOid4vpViewSteps {
    LOADING_QRCODE, PRESENTING_QRCODE, GETTING_STATUS, DISPLAYING_CREDENTIAL
}

@Composable
fun VerifyDelegatedOid4vpView(
    navController: NavController
) {
    var step by remember { mutableStateOf(VerifyDelegatedOid4vpViewSteps.LOADING_QRCODE) }
    var status by remember { mutableStateOf(VerifyDelegatedOid4vpViewStatus.INITIATED) }
    var verifier by remember { mutableStateOf<DelegatedVerifier?>(null) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorDescription by remember { mutableStateOf<String?>(null) }
    var authQuery by remember { mutableStateOf<String?>(null) }
    var uri by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var presentation by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        verifier = DelegatedVerifier.newClient()
        try {
            verifier?.requestDelegatedVerification(url = "https://cdn-icons-png.flaticon.com/512/2202/2202112.png")
        } catch (e: Exception) {
            errorTitle = "Failed getting QR Code"
            errorDescription = e.localizedMessage
        }

        // get QR CODE Data
        // save information and update step to step.PRESENTING_QRCODE
        // call function to start monitoring status (status.INITIATED)
    }

    fun monitorStatus(status: VerifyDelegatedOid4vpViewStatus) {
        // get status
        // if failed -> set error
        // else -> update variables, call monitorStatus
    }

    fun back() {
        navController.navigate(Screen.HomeScreen.route) {
            popUpTo(0)
        }
    }

    if (errorTitle != null && errorDescription != null) {
        ErrorView(
            errorTitle = errorTitle!!,
            errorDetails = errorDescription!!,
            onClose = {
                back()
            }
        )
    } else {
        when(step) {
            VerifyDelegatedOid4vpViewSteps.LOADING_QRCODE -> {
                LoadingView(
                    loadingText = "Getting QR Code",
                    cancelButtonLabel = "Cancel",
                    onCancel = {
                        back()
                    }
                )
            }
            VerifyDelegatedOid4vpViewSteps.PRESENTING_QRCODE -> {
                Text("uri")
            }
            VerifyDelegatedOid4vpViewSteps.GETTING_STATUS -> {
                LoadingView(
                    loadingText = "Verifying status - $status",
                    cancelButtonLabel = "Cancel",
                    onCancel = {
                        back()
                    }
                )
            }
            VerifyDelegatedOid4vpViewSteps.DISPLAYING_CREDENTIAL -> {
                Text("presentation")
            }
        }
    }
}