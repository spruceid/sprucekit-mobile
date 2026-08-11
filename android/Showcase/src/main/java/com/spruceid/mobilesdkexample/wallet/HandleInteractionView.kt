package com.spruceid.mobilesdkexample.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.rs.discoverProtocols
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.navigation.Screen


@Composable
fun HandleInteractionView(
    navController: NavController,
    url: String,
    credentialPackId: String?
) {
    var protocols by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sheetOpen by remember { mutableStateOf(false) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorDescription by remember { mutableStateOf<String?>(null) }

    suspend fun loadProtocols() {
        try {
            var discoveryUrl = url
            // Remove `interaction:` prefix if present
            if (discoveryUrl.startsWith("interaction:", ignoreCase = true)) {
                discoveryUrl = discoveryUrl.drop("interaction:".length)
            }
            protocols = discoverProtocols(discoveryUrl)
            sheetOpen = true

        } catch (e: Exception) {
            errorTitle = "Error discovering protocols"
            errorDescription = "Couldn't discover protocols from QR Code payload ${url}. Error: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        loadProtocols()
    }

    fun onBack() {
        navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
    }


    if (sheetOpen) {
        ProtocolSelectorBottomSheet( { onBack() }, protocols=protocols, navController=navController, credentialPackId=credentialPackId)
    } else if (errorTitle != null && errorDescription != null) {
        ErrorView(
            errorTitle = errorTitle!!,
            errorDetails = errorDescription!!,
            onClose = { onBack() }
        )
    } else {
        LoadingView(loadingText = "Discovering protocols...")
    }
}





