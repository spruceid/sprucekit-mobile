package com.spruceid.mobilesdkexample.verifier

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.rs.DecodedVcbVdl
import com.spruceid.mobile.sdk.rs.decodeVcbVdlToJson
import com.spruceid.mobile.sdk.rs.verifyVcbVdlJsonSignature
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyVcbVdlView(
    navController: NavController
) {
    val ctx = LocalContext.current
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

    var error by remember {
        mutableStateOf("")
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
                error = e.message ?: e.toString()
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
    } else if (success == true) {
        VerifierBarCodeSuccessView(
            jsonCredential = decodedVcbVdl!!.jsonValue(),
            isValid = isValid!!,
            onClose = ::back,
            onRestart = ::restart,
            allDataContent = {
                VcbVdlAllDataView(
                    qrCodeCredential = qrCodeContent!!,
                    cborCredential = decodedVcbVdl!!.cborValue(),
                    jsonCredential = decodedVcbVdl!!.jsonValue()
                )
            }
        )
    } else {
        ErrorView(
            "Failed to verify VCB VDL",
            errorDetails = error,
            onClose = ::back
        )
    }
}

@Composable
private fun VcbVdlAllDataView(
    qrCodeCredential: String,
    cborCredential: String,
    jsonCredential: String
) {
    var qrCodeExpanded by remember { mutableStateOf(false) }
    var cborExpanded by remember { mutableStateOf(false) }
    var jsonExpanded by remember { mutableStateOf(false) }

    // Format JSON-LD with proper indentation
    val formattedJson = remember(jsonCredential) {
        try {
            JSONObject(jsonCredential).toString(4)
        } catch (e: Exception) {
            jsonCredential
        }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Raw QR Code Accordion
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { qrCodeExpanded = !qrCodeExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Raw QR Code",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950
                )
                Icon(
                    imageVector = if (qrCodeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (qrCodeExpanded) "Collapse" else "Expand",
                    tint = ColorStone600
                )
            }
            if (qrCodeExpanded) {
                Text(
                    text = qrCodeCredential,
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = ColorStone600,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
            HorizontalDivider(color = ColorStone200)
        }

        // Raw CBOR Accordion
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { cborExpanded = !cborExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Raw CBOR",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950
                )
                Icon(
                    imageVector = if (cborExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (cborExpanded) "Collapse" else "Expand",
                    tint = ColorStone600
                )
            }
            if (cborExpanded) {
                Text(
                    text = cborCredential,
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = ColorStone600,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
            HorizontalDivider(color = ColorStone200)
        }

        // Raw JSON-LD Accordion
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { jsonExpanded = !jsonExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Raw JSON-LD",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950
                )
                Icon(
                    imageVector = if (jsonExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (jsonExpanded) "Collapse" else "Expand",
                    tint = ColorStone600
                )
            }
            if (jsonExpanded) {
                Text(
                    text = formattedJson,
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = ColorStone600,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
            HorizontalDivider(color = ColorStone200)
        }
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