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
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyDLView(
    navController: NavController,
) {
    var success by remember {
        mutableStateOf<Boolean?>(null)
    }

    fun onRead(content: String) {
        GlobalScope.launch {
            try {
                verifyPdf417Barcode(payload = content)
                success = true
            } catch (e: Exception) {
                success = false
                e.printStackTrace()
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

    if (success == null) {
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