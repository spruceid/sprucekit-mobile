package com.spruceid.mobilesdkexample.verifier

import android.os.Handler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.rs.verifyVcbQrcodeAgainstMrz
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

enum class VerifyEASteps {
    STEP_ONE, INTERMEDIATE, STEP_TWO, SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyEAView(
    navController: NavController,
    verificationActivityLogsViewModel: VerificationActivityLogsViewModel,
) {
    var step by remember { mutableStateOf(VerifyEASteps.STEP_ONE) }
    var success by remember { mutableStateOf<Boolean?>(null) }
    var stepOne by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf<Boolean>(false) }

    fun onReadStepOne(content: String) {
        stepOne = content
        step = VerifyEASteps.INTERMEDIATE
        Handler().postDelayed({
            step = VerifyEASteps.STEP_TWO
        }, 1000)
    }

    fun onReadStepTwo(content: String) {
        if (!verifying) {
            verifying = true
            GlobalScope.launch {
                try {
                    verifyVcbQrcodeAgainstMrz(mrzPayload = content, qrPayload = stepOne!!)
                    success = true
                    verificationActivityLogsViewModel.saveVerificationActivityLog(
                        VerificationActivityLogs(
                            credentialTitle = "Employment Authorization",
                            issuer = "State of Utopia",
                            status = "VALID",
                            verificationDateTime = getCurrentSqlDate(),
                            additionalInformation = ""
                        )
                    )
                } catch (e: Exception) {
                    success = false
                    e.printStackTrace()
                }
                step = VerifyEASteps.SUCCESS
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
    } else {
        when (step) {
            VerifyEASteps.STEP_ONE -> {
                ScanningComponent(
                    subtitle = "Scan the front of your\nemployment authorization",
                    scanningType = ScanningType.QRCODE,
                    onRead = ::onReadStepOne,
                    onCancel = ::back
                )
            }

            VerifyEASteps.INTERMEDIATE -> {
                LoadingView(
                    loadingText = "Verifying..."
                )
            }

            VerifyEASteps.STEP_TWO -> {
                ScanningComponent(
                    title = "Scan MRZ",
                    subtitle = "Scan the back of your document",
                    scanningType = ScanningType.MRZ,
                    onRead = ::onReadStepTwo,
                    onCancel = ::back
                )
            }

            VerifyEASteps.SUCCESS -> {
                VerifierBinarySuccessView(
                    success = success!!,
                    description = if (success!!) "Valid Employment Authorization" else "Invalid Employment Authorization",
                    onClose = ::back
                )
            }
        }
    }
}