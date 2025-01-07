package com.spruceid.mobilesdkexample.verifier

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.db.VerificationMethods
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AddVerificationMethodView(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel
) {
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var qrcode by remember { mutableStateOf<String?>(null) }

    fun back() {
        navController.navigate(
            Screen.HomeScreen.route.replace("{tab}", "verifier")
        ) {
            popUpTo(0)
        }
    }

    fun onRead(content: String) {
        qrcode = content
        scope.launch {
            try {
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)
                    val credentialName = json.getString("credential_name")
                    verificationMethodsViewModel.saveVerificationMethod(
                        VerificationMethods(
                            type = json.getString("type"),
                            name = credentialName,
                            description = "Verifies $credentialName Credentials",
                            verifierName = json.getString("verifier_name"),
                            url = json.getString("url")
                        )
                    )
                }
                back()
            } catch (e: Exception) {
                err = e.localizedMessage
            }
        }
    }

    if (err != null) {
        ErrorView(
            errorTitle = "Error adding verification method",
            errorDetails = err!!,
            onClose = { back() }
        )
    } else if (qrcode == null) {
        ScanningComponent(
            subtitle = "Scan Verification QR Code",
            scanningType = ScanningType.QRCODE,
            onRead = ::onRead,
            onCancel = ::back
        )
    } else {
        LoadingView(
            loadingText = "Storing Verification Method..."
        )
    }

}