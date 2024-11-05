package com.spruceid.mobilesdkexample.verifier

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.IsoMdlReader
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobile.sdk.rs.MDocItem
import com.spruceid.mobile.sdk.ui.QRCodeScanner
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.utils.checkAndRequestBluetoothPermissions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

val trustAnchorCerts = listOf(
    """
-----BEGIN CERTIFICATE-----
MIICLjCCAdSgAwIBAgIUXrDPrioY9PCUO5hCXJTsVIZwI0cwCgYIKoZIzj0EAwIw
UDELMAkGA1UEBhMCVVMxDjAMBgNVBAgMBVVTLUNBMQwwCgYDVQQKDANETVYxIzAh
BgNVBAMMGkNhbGlmb3JuaWEgRE1WIFJvb3QgQ0EgVUFUMB4XDTIzMDQxNDE3MjQ0
N1oXDTMzMDIyMDE3MjQ0N1owUDELMAkGA1UEBhMCVVMxDjAMBgNVBAgMBVVTLUNB
MQwwCgYDVQQKDANETVYxIzAhBgNVBAMMGkNhbGlmb3JuaWEgRE1WIFJvb3QgQ0Eg
VUFUMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEORXa3DGvoGS0s6S71Vw5oKEw
DdPubWyg75A2p1hca4b66MA0LXUWjoz1cIKUJpyEket9ajEx5+hQvB3yvycKVqOB
izCBiDAdBgNVHQ4EFgQUSWhCfS8C3wEPseC28ScmFn0j25UwEgYDVR0TAQH/BAgw
BgEB/wIBADALBgNVHQ8EBAMCAQYwHQYDVR0SBBYwFIESZXhhbXBsZUBkbXYuY2Eu
Z292MCcGA1UdHwQgMB4wHKAaoBiGFmh0dHBzOi8vZG12LmNhLmdvdi9jcmwwCgYI
KoZIzj0EAwIDSAAwRQIgbpRE08g3aId6e2QULkmlTqnGq0+6kqLltHfjmO6/5MEC
IQDJdY3WnkpFhPw+Ej3ANlFzwvaDgfaacODGR0pFvBQwlg==
-----END CERTIFICATE-----"""
)

val defaultElements: Map<String, Map<String, Boolean>> =
    mapOf(
        "org.iso.18013.5.1" to mapOf(
            "portrait" to false,
            "family_name" to false,
            "given_name" to false,
            "birth_date" to false,
            "expiry_date" to false,
            "sex" to false,
            "height" to false,
            "weight" to false,
            "eye_colour" to false,
            "hair_colour" to false,
            "resident_address" to false,
            "document_number" to false
        ),
        "org.iso.18013.5.1.aamva" to mapOf(
            "DHS_compliance" to false,
            "domestic_driving_privileges" to false,
            "veteran" to false
        )
    )

enum class State {
    SCANNING, TRANSMITTING, DONE
}

@OptIn(
    ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class,
    ExperimentalPermissionsApi::class
)
@Composable
fun VerifyMDocView(navController: NavController) {
    val context = LocalContext.current
    var reader: IsoMdlReader? = null

    var scanProcessState by remember {
        mutableStateOf(State.SCANNING)
    }

    var result by remember {
        mutableStateOf<Map<String, Map<String, MDocItem>>?>(null)
    }

    var isBluetoothEnabled by remember {
        mutableStateOf(getBluetoothManager(context)!!.adapter.isEnabled)
    }

    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val areGranted = permissionsMap.values.reduce { acc, next -> acc && next }
        if (!areGranted) {
            // @TODO: Show dialog
        }
    }

    val bleCallback: BLESessionStateDelegate = object : BLESessionStateDelegate() {
        override fun update(state: Map<String, Any>) {
            Log.d("VerifyMDocView", state.toString())
            if (state.containsKey("mdl")) {
                result = reader?.handleResponse(state["mdl"] as ByteArray)
                scanProcessState = State.DONE
            }
        }

        override fun error(error: Exception) {
            TODO("Not yet implemented")
        }
    }

    fun elementMapToList(elements: Map<String, Map<String, Boolean>>): List<String> {
        val elementList = listOf(elements.values.map { it.keys.toList() })
        return elementList.flatten().flatten()
    }

    fun onRead(content: String) {
        scanProcessState = State.TRANSMITTING
        checkAndRequestBluetoothPermissions(
            context,
            getPermissions().toTypedArray(),
            launcherMultiplePermissions
        )
        Log.d("VerifyMDocView", "Scanned: $content")
        val bluetooth = getBluetoothManager(context)
        GlobalScope.launch {
            reader = IsoMdlReader(
                bleCallback,
                content,
                defaultElements,
                trustAnchorCerts,
                bluetooth!!,
                context
            )
        }
    }

    fun onCancel() {
        navController.popBackStack()
    }

    val elementsList = elementMapToList(defaultElements)

    when (scanProcessState) {
        State.SCANNING -> Column {
            ScanningComponent(
                navController,
                ScanningType.QRCODE,
                title = "",
                subtitle = "",
                onRead = ::onRead,
                onCancel = ::onCancel
            )
            HorizontalDivider(thickness = 1.dp)
            Text("Requesting the following:")
            FlowColumn(Modifier.fillMaxWidth()) {
                repeat(elementsList.size) {
                    Box(
                        Modifier
                            .fillMaxColumnWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = elementsList[it],
                        )
                    }
                }
            }
        }

        State.TRANSMITTING -> LoadingView("Verifying...", "Cancel", ::onCancel)
        State.DONE -> VerifierMDocResultView(
            navController = navController,
            result = result!!,
        )
    }
}