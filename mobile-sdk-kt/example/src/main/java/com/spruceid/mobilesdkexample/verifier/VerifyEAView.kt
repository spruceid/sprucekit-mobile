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
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

enum class VerifyEASteps {
    STEP_ONE, INTERMEDIATE, STEP_TWO, SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyEAView(
    navController: NavController
) {
    var step by remember {
        mutableStateOf(VerifyEASteps.STEP_ONE)
    }

    var success by remember {
        mutableStateOf<Boolean?>(null)
    }

    var stepOne by remember {
        mutableStateOf<String?>(null)
    }

    fun onReadStepOne(content: String) {
        stepOne = content
        step = VerifyEASteps.INTERMEDIATE
        Handler().postDelayed({
            step = VerifyEASteps.STEP_TWO
        }, 1000)
    }

    fun onReadStepTwo(content: String) {
        success = true
        GlobalScope.launch {
            try {
                verifyVcbQrcodeAgainstMrz(mrzPayload = content, qrPayload = stepOne!!)
                success = true
            } catch (e: Exception) {
                success = false
                e.printStackTrace()
            }
            step = VerifyEASteps.SUCCESS
        }
    }

    when (step) {
        VerifyEASteps.STEP_ONE -> {
            ScanningComponent(
                subtitle = "Scan the front of your\nemployment authorization",
                navController = navController,
                scanningType = ScanningType.QRCODE,
                onRead = ::onReadStepOne
            )
        }

        VerifyEASteps.INTERMEDIATE -> {
            Loader()
        }

        VerifyEASteps.STEP_TWO -> {
            ScanningComponent(
                title = "Scan MRZ",
                subtitle = "Scan the back of your document",
                navController = navController,
                scanningType = ScanningType.MRZ,
                onRead = ::onReadStepTwo
            )
        }

        VerifyEASteps.SUCCESS -> {
            VerifierBinarySuccessView(
                navController = navController,
                success = success!!,
                description = if (success!!) "Valid Employment Authorization" else "Invalid Employment Authorization"
            )
        }
    }
}