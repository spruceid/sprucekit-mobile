package com.spruceid.mobilesdkexample.verifier

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.rs.verifyPdf417Barcode
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyDLView(
    navController: NavController,
    verificationActivityLogsViewModel: VerificationActivityLogsViewModel,
) {
    var success by remember { mutableStateOf<Boolean?>(null) }
    var verifying by remember { mutableStateOf<Boolean>(false) }


    fun onRead(content: String) {
        if (!verifying) {
            verifying = true
            GlobalScope.launch {
                try {
                    verifyPdf417Barcode(payload = content)
                    success = true
                    verificationActivityLogsViewModel.saveVerificationActivityLog(
                        VerificationActivityLogs(
                            credentialTitle = "Driver's License",
                            issuer = "Utopia Department of Motor Vehicles",
                            status = "VALID",
                            verificationDateTime = getCurrentSqlDate(),
                            additionalInformation = ""
                        )
                    )
                } catch (e: Exception) {
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
            subtitle = "Scan the\nback of your driver's license",
            scanningType = ScanningType.PDF417,
            onRead = ::onRead,
            onCancel = ::back
        )
    } else {
        VerifierBinarySuccessView(
            success = success!!,
            description = if (success!!) "Valid Driver's License" else "Invalid Driver's License",
            onClose = ::back
        )
    }
}