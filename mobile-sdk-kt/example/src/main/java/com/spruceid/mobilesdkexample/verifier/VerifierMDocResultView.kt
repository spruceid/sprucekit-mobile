package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.convertToJson
import com.spruceid.mobile.sdk.rs.AuthenticationStatus
import com.spruceid.mobile.sdk.rs.MDocItem
import com.spruceid.mobilesdkexample.credentials.genericObjectDisplayer
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.ErrorToast
import com.spruceid.mobilesdkexample.utils.SimpleAlertDialog
import com.spruceid.mobilesdkexample.utils.WarningToast

@Composable
fun VerifierMDocResultView(
    result: Map<String, Map<String, MDocItem>>,
    issuerAuthenticationStatus: AuthenticationStatus,
    deviceAuthenticationStatus: AuthenticationStatus,
    responseProcessingErrors: String? = null,
    onClose: () -> Unit,
    logVerification: (String, String, String) -> Unit,
) {
    val mdoc by remember { mutableStateOf(convertToJson(result)) }
    val title = "Mobile Drivers License"
    var issuer by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            issuer = mdoc.getJSONObject("org.iso.18013.5.1").getString("issuing_authority")
        } catch (_: Exception) {
        }
        // @TODO: Log verification with real status
        logVerification(title, issuer ?: "", "VALID")
    }

    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .padding(top = 30.dp)
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = title,
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = ColorStone950,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            issuer?.let {
                Text(
                    text = it,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = ColorStone600
                )
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
        }
        Column(
            Modifier
                .fillMaxSize()
                .weight(weight = 1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                SimpleAlertDialog(
                    message = responseProcessingErrors,
                    trigger = {
                        when (deviceAuthenticationStatus) {
                            AuthenticationStatus.VALID -> null
                            AuthenticationStatus.INVALID -> ErrorToast("Device not authenticated")
                            AuthenticationStatus.UNCHECKED -> WarningToast("Device not checked")
                        }
                        when (issuerAuthenticationStatus) {
                            AuthenticationStatus.VALID -> null
                            AuthenticationStatus.INVALID -> ErrorToast("Issuer not authenticated")
                            AuthenticationStatus.UNCHECKED -> WarningToast("Issuer not checked")
                        }
                    }
                )
            }

            genericObjectDisplayer(
                mdoc,
                listOf()
            )
        }

        Button(
            onClick = {
                onClose()
            },
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = ColorStone950,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = ColorStone300,
                    shape = RoundedCornerShape(6.dp)
                )
        ) {
            Text(
                text = "Close",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                color = ColorStone950,
            )
        }
    }
}