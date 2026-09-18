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
import com.spruceid.mobile.sdk.rs.VerifiedDocument
import com.spruceid.mobilesdkexample.credentials.genericObjectDisplayer
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.ErrorToast
import com.spruceid.mobilesdkexample.utils.SimpleAlertDialog
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName
import org.json.JSONObject

/** One verified document, prepared for display. */
private data class MDocResultSection(
    val title: String,
    val issuer: String?,
    val elements: JSONObject,
)

/**
 * The issuing authority a document claims, or null. This is the document's own claim about its
 * issuer, digest-verified along with every other element.
 */
private fun claimedIssuer(namespaces: JSONObject): String? {
    for (key in namespaces.keys()) {
        try {
            val authority = namespaces.getJSONObject(key).optString("issuing_authority", "")
            if (authority.isNotBlank()) {
                return authority
            }
        } catch (_: Exception) {
        }
    }
    return null
}

@Composable
fun VerifierMDocResultView(
    documents: List<VerifiedDocument>,
    failedDocTypes: List<String>,
    responseProcessingErrors: String? = null,
    onClose: () -> Unit,
    logVerification: (String, String, String) -> Unit,
) {
    // One section per verified document, in the order the holder returned them. Kept apart
    // rather than merged into one map: two documents may share a namespace, and merging would
    // let one silently overwrite the other's elements.
    val sections by remember {
        mutableStateOf(
            documents.map { document ->
                val elements = convertToJson(document.namespaces)
                MDocResultSection(
                    title = credentialTypeDisplayName(document.docType),
                    issuer = claimedIssuer(elements),
                    elements = elements,
                )
            }
        )
    }
    // A response where every document failed has nothing verified to name, so fall back to the
    // claimed labels -- otherwise the failure that matters most renders untitled.
    val title = sections.firstOrNull()?.title
        ?: credentialTypeDisplayName(failedDocTypes.firstOrNull() ?: "")
    val issuer = sections.firstOrNull()?.issuer

    LaunchedEffect(Unit) {
        // One log entry per response, as before, named after the first verified document.
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

            sections.forEach { section ->
                // A single document is already named by the header, so only label sections when
                // there is more than one to tell apart.
                if (sections.size > 1) {
                    Text(
                        text = section.title,
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = ColorStone950,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                genericObjectDisplayer(
                    section.elements,
                    listOf()
                )
            }
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