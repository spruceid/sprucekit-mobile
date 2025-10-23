package com.spruceid.mobilesdkexample.verifier

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.rs.DecodedVcbVdl
import com.spruceid.mobile.sdk.rs.decodeVcbVdlToJson
import com.spruceid.mobile.sdk.rs.verifyJwtVp
import com.spruceid.mobile.sdk.rs.verifyVcbVdlJsonSignature
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyVcbVdlView(
    navController: NavController
) {
    var ctx = LocalContext.current
    var success by remember {
        mutableStateOf<Boolean?>(null)
    }
    var isValid by remember {
        mutableStateOf<Boolean?>(null)
    }

    var decodedVcbVdl by remember {
        mutableStateOf<DecodedVcbVdl?>(null)
    }

    var qrCodeContent by remember {
        mutableStateOf<String?>(null)
    }

    fun onRead(content: String) {
        GlobalScope.launch {
            try {
                qrCodeContent = content
                val context = getVcbVdlContext(ctx)

                decodedVcbVdl = decodeVcbVdlToJson(barcodeString = content, contexts = context)
                isValid = verifyVcbVdlJsonSignature(decodedVcbVdl!!.jsonValue())
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

    fun restart() {
        success = null
        isValid = null
        decodedVcbVdl = null
        qrCodeContent = null
    }

    if (success == null) {
        ScanningComponent(
            scanningType = ScanningType.QRCODE,
            onRead = ::onRead,
            onCancel = ::back
        )
    } else {
        VerifierVcbVdlSuccessView(
            qrCodeCredential = qrCodeContent!!,
            jsonCredential = decodedVcbVdl!!.jsonValue(),
            cborCredential = decodedVcbVdl!!.cborValue(),
            isValid = isValid!!,
            onClose = ::back,
            onRestart = ::restart
        )
    }
}

fun getVcbVdlContext(ctx: Context): Map<String, String> {
    val context = mutableMapOf<String, String>()

    context["https://w3id.org/vdl/v2"] =
        ctx.resources
            .openRawResource(R.raw.w3id_org_vdl_v2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://www.w3.org/ns/credentials/v2"] =
        ctx.resources
            .openRawResource(R.raw.w3_org_ns_credentials_v2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    return context
}