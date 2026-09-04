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
import com.spruceid.mobile.sdk.rs.MDocItem
import com.spruceid.mobilesdkexample.credentials.genericObjectDisplayer
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.ErrorToast
import com.spruceid.mobilesdkexample.utils.SimpleAlertDialog
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName

@Composable
fun VerifierMDocResultView(
    result: Map<String, Map<String, MDocItem>>,
    docTypes: List<String>,
    responseProcessingErrors: String? = null,
    onClose: () -> Unit,
    logVerification: (String, String, String) -> Unit,
) {
    val mdoc by remember { mutableStateOf(convertToJson(result)) }
    val title = credentialTypeDisplayName(docTypes.firstOrNull() ?: "")
    var issuer by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Try to find the issuing authority from any namespace. The element identifier is
        // namespace-specific: ISO 18013-5 uses `issuing_authority`, ISO 23220-2 uses
        // `issuing_authority_unicode`.
        for (key in mdoc.keys()) {
            try {
                val namespace = mdoc.getJSONObject(key)
                val authority = listOf("issuing_authority", "issuing_authority_unicode")
                    .firstNotNullOfOrNull { namespace.optString(it, "").ifBlank { null } }
                if (authority != null) {
                    issuer = authority
                    break
                }
            } catch (_: Exception) {
            }
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
                // Whatever was verified is always shown; anything that went wrong is always
                // reported alongside it. Elements come only from documents that passed every
                // check, and a document that failed always contributes at least one error here.
                SimpleAlertDialog(
                    message = responseProcessingErrors,
                    trigger = {
                        if (responseProcessingErrors != null) {
                            ErrorToast("Verification errors")
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