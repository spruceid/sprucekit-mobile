package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.rs.Cwt
import com.spruceid.mobile.sdk.rs.verifyPdf417Barcode
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.utils.CryptoImpl
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyCwtView(
    navController: NavController,
    verificationActivityLogsViewModel: VerificationActivityLogsViewModel,
    statusListViewModel: StatusListViewModel,
) {
    var success by remember { mutableStateOf<Boolean?>(null) }
    var verifying by remember { mutableStateOf<Boolean>(false) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }


    fun onRead(content: String) {
        if (!verifying) {
            verifying = true
            GlobalScope.launch {
                try {
                    code = content
                    Cwt.newFromBase10(code).verify(CryptoImpl())
                    success = true
                    // TODO: add log
                } catch (e: Exception) {
                    error = e.toString()
                    success = false
                    e.printStackTrace()
                }
                verifying = false
            }
        }
    }

    fun back() {
        navController.navigate(
            Screen.HomeScreen.route.replace("{tab}", "verifier")
        ) {
            popUpTo(0)
        }
    }

    if (verifying) {
        LoadingView(loadingText = "Verifying...")
    } else if (success == null) {
        ScanningComponent(
            scanningType = ScanningType.QRCODE,
            onRead = ::onRead,
            onCancel = ::back
        )
    } else if (success == true) {

        VerifierCredentialSuccessView(
            rawCredential = code,
            onClose = { back() },
            logVerification = { title, issuer, status ->
                GlobalScope.launch {
                    verificationActivityLogsViewModel.saveVerificationActivityLog(
                        VerificationActivityLogs(
                            credentialTitle = title,
                            issuer = issuer,
                            status = status,
                            verificationDateTime = getCurrentSqlDate(),
                            additionalInformation = ""
                        )
                    )
                }
            },
            statusListViewModel = statusListViewModel
        )
    } else {
        ErrorView("Failed to verify CWT", errorDetails = error, onClose = ::back)
    }
}