package com.spruceid.mobilesdkexample.verifier

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
import com.spruceid.mobile.sdk.rs.DecodedPdf417Aamva
import com.spruceid.mobile.sdk.rs.decodePdf417AamvaFromPayload
import com.spruceid.mobile.sdk.rs.verifyPdf417AamvaSignature
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject


@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifyPdf417AamvaView(
    navController: NavController
) {

    var PDF417_AAMVA_NEVADA_PUBLIC_KEY = """
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5UfhdL6MSLJwnwwndiBtsjLURvIn
QesCz68mZEqlztk+6BJYZeWiawyzIYv/BOHRWDo5VBuybIrK/grjVHy/1w==
-----END PUBLIC KEY-----"""


    var ctx = LocalContext.current
    var success by remember {
        mutableStateOf<Boolean?>(null)
    }
    var isValid by remember {
        mutableStateOf<Boolean?>(null)
    }

    var decodedPdf417Aamva  by remember {
        mutableStateOf<DecodedPdf417Aamva?>(null)
    }

    var barCodeContent by remember {
        mutableStateOf<String?>(null)
    }

    var error by remember {
        mutableStateOf("")
    }

    fun onRead(content: String) {
        GlobalScope.launch {
            try {
                barCodeContent = content
                decodedPdf417Aamva = decodePdf417AamvaFromPayload(payload = content)
                isValid = verifyPdf417AamvaSignature(decoded = decodedPdf417Aamva!!, publicKeyPem = PDF417_AAMVA_NEVADA_PUBLIC_KEY)

                success = true
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                success = false
                e.printStackTrace()
            }
        }
    }

    // Shenanigans to transform it to a JSON-LD
    fun transformAamvaFieldsToReadable(dlFieldsJson: String): String {
        val dlFields = JSONObject(dlFieldsJson)
        val readableFields = JSONObject()

        // Map AAMVA codes to human-readable names
        val fieldMapping = mapOf(
            "DCS" to "family_name",
            "DAC" to "given_name",
            "DAD" to "middle_name",
            "DBB" to "birth_date",
            "DBA" to "expiry_date",
            "DBD" to "issue_date",
            "DAY" to "eye_colour",
            "DAU" to "height",
            "DAW" to "weight",
            "DBC" to "sex",
            "DAZ" to "hair_colour",
            "DAG" to "resident_address",
            "DAH" to "address_street_2",
            "DAI" to "resident_city",
            "DAJ" to "resident_state",
            "DAK" to "resident_postal_code",
            "DAQ" to "document_number",
            "DCF" to "document_discriminator",
            "DCG" to "issuing_country",
            "DCA" to "vehicle_class",
            "DCB" to "restrictions",
            "DCD" to "endorsements",
            "DDE" to "family_name_truncation",
            "DDF" to "given_name_truncation",
            "DDG" to "middle_name_truncation",
            "DCI" to "place_of_birth",
            "DCJ" to "audit_information",
            "DCK" to "inventory_control_number",
            "DBN" to "alias_family_name",
            "DBG" to "alias_given_name",
            "DBS" to "alias_suffix",
            "DCU" to "name_suffix"
        )

        // Transform each field
        for (key in dlFields.keys()) {
            val readableName = fieldMapping[key] ?: key
            readableFields.put(readableName, dlFields.getString(key))
        }

        // Wrap in proper Verifiable Credential structure
        val vcStructure = JSONObject()
        vcStructure.put("@context", JSONArray().apply {
            put("https://www.w3.org/2018/credentials/v1")
        })
        vcStructure.put("type", JSONArray().apply {
            put("VerifiableCredential")
            put("PDF417AamvaDL")
        })

        // Add issuer
        val issuerObj = JSONObject()
        issuerObj.put("id", "did:key:zDnaeoLyiMWjCMbgH5mBSvusfk534bDnua362RNwVKmwdgAKc") //TODO: Just change that
        issuerObj.put("name", "Spruce Systems Inc.")
        vcStructure.put("issuer", issuerObj)

        // Add credential subject with readable fields
        vcStructure.put("credentialSubject", readableFields)

        return vcStructure.toString()
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
        decodedPdf417Aamva = null
        barCodeContent = null
    }

    if (success == null) {
        ScanningComponent(
            scanningType = ScanningType.PDF417,
            onRead = ::onRead,
            onCancel = ::back
        )
    } else if (success == true) {
        val readableJson = transformAamvaFieldsToReadable(
            decodedPdf417Aamva!!.dlFieldsJson(),
        )

        VerifierBarCodeSuccessView(
            jsonCredential = readableJson,
            isValid = isValid!!,
            onClose = ::back,
            onRestart = ::restart,
            allDataContent = {
                Pdf417AamvaAllDataView(
                    barCodeContent = barCodeContent!!,
                    znFieldsJson = decodedPdf417Aamva!!.znFieldsJson(),
                    dlFieldsJson = decodedPdf417Aamva!!.dlFieldsJson()
                )
            }
        )
    } else {
        ErrorView(
            "Failed to verify PDF417 AAMVA",
            errorDetails = error,
            onClose = ::back
        )
    }
}

@Composable
private fun Pdf417AamvaAllDataView(
    barCodeContent: String,
    znFieldsJson: String,
    dlFieldsJson: String
) {
    var barcodeExpanded by remember { mutableStateOf(false) }
    var znExpanded by remember { mutableStateOf(false) }
    var dlExpanded by remember { mutableStateOf(false) }

    // Format JSON with proper indentation
    val formattedZnJson = remember(znFieldsJson) {
        try {
            JSONObject(znFieldsJson).toString(4)
        } catch (e: Exception) {
            znFieldsJson
        }
    }

    val formattedDlJson = remember(dlFieldsJson) {
        try {
            JSONObject(dlFieldsJson).toString(4)
        } catch (e: Exception) {
            dlFieldsJson
        }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Raw Barcode Content Accordion
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { barcodeExpanded = !barcodeExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Raw Barcode Content",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950
                )
                Icon(
                    imageVector = if (barcodeExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (barcodeExpanded) "Collapse" else "Expand",
                    tint = ColorStone600
                )
            }
            if (barcodeExpanded) {
                Text(
                    text = barCodeContent,
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

        // ZN Fields JSON Accordion
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { znExpanded = !znExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ZN Fields (JSON)",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950
                )
                Icon(
                    imageVector = if (znExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (znExpanded) "Collapse" else "Expand",
                    tint = ColorStone600
                )
            }
            if (znExpanded) {
                Text(
                    text = formattedZnJson,
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

        // DL Fields JSON Accordion
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { dlExpanded = !dlExpanded }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DL Fields (JSON)",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950
                )
                Icon(
                    imageVector = if (dlExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (dlExpanded) "Collapse" else "Expand",
                    tint = ColorStone600
                )
            }
            if (dlExpanded) {
                Text(
                    text = formattedDlJson,
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