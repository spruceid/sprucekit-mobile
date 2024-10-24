package com.spruceid.mobilesdkexample.wallet

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// The scheme for the OID4VP QR code.
const val OPEN_ID4VP_SCHEME = "openid4vp://"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DispatchQRView(
    navController: NavController,
) {
    val scope = rememberCoroutineScope()

    fun onRead(url: String) {
        scope.launch {
            if (url.startsWith(OPEN_ID4VP_SCHEME)) {
                val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())

                navController.navigate("oid4vp/$encodedUrl") {
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    ScanningComponent(
        navController = navController,
        scanningType = ScanningType.QRCODE,
        onRead = ::onRead
    )
}
