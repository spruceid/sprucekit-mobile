package com.spruceid.mobilesdkexample.credentials.credentialDetailsView

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.wallet.DispatchQRView
import com.spruceid.mobilesdkexample.wallet.SupportedQRTypes

@Composable
fun ScanModeContent(
    navController: NavController,
    credentialPackId: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBase50)
            .clip(RoundedCornerShape(0.dp))
    ) {
        DispatchQRView(
            navController = navController,
            credentialPackId = credentialPackId,
            supportedTypes = listOf(SupportedQRTypes.OID4VP, SupportedQRTypes.HTTP),
            backgroundColor = ColorBase50,
            hideCancelButton = true,
            isInsideCredentialDetails = true
        )
    }
}

@Composable
fun ShareModeContent(
    credentialPack: CredentialPack?,
    genericCredentialDetailsShareQRCode: @Composable (CredentialPack) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBase50),
        contentAlignment = Alignment.Center
    ) {
        credentialPack?.let { pack ->
            genericCredentialDetailsShareQRCode(pack)
        }
    }
}
