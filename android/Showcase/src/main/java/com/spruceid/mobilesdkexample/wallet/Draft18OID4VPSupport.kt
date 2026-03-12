package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.Draft18PresentableCredential
import com.spruceid.mobile.sdk.rs.Draft18PresentationSigner
import com.spruceid.mobile.sdk.rs.Draft18RequestedField
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import org.json.JSONObject

class Draft18Signer(keyId: String?) : Draft18PresentationSigner {
    private val keyId = keyId ?: DEFAULT_SIGNING_KEY_ID
    private val keyManager = KeyManager()
    private var jwk: String
    private val didJwk = DidMethodUtils(DidMethod.JWK)

    init {
        if (!keyManager.keyExists(this.keyId)) {
            keyManager.generateSigningKey(id = this.keyId)
        }
        this.jwk = keyManager.getJwk(this.keyId)?.toString()
            ?: throw IllegalArgumentException("Invalid kid")
    }

    override suspend fun sign(payload: ByteArray): ByteArray {
        return keyManager.signPayload(keyId, payload)
            ?: throw IllegalStateException("Failed to sign payload")
    }

    override fun algorithm(): String {
        val json = JSONObject(jwk)
        return try {
            json.getString("alg")
        } catch (_: Exception) {
            "ES256"
        }
    }

    override suspend fun verificationMethod(): String {
        return didJwk.vmFromJwk(jwk)
    }

    override fun did(): String {
        return didJwk.didFromJwk(jwk)
    }

    override fun jwk(): String {
        return jwk
    }

    override fun cryptosuite(): String {
        return "ecdsa-rdfc-2019"
    }
}

data class Draft18CredentialRequirement(
    val descriptorId: String,
    val displayName: String,
    val credentials: List<Draft18PresentableCredential>,
)

@Composable
fun Draft18DataFieldSelector(
    selectedCredential: Draft18PresentableCredential,
    requestedFields: List<Draft18RequestedField>,
    currentIndex: Int = 0,
    totalCount: Int = 1,
    onContinue: (selectedFields: List<String>) -> Unit,
    onCancel: () -> Unit,
    allClaims: JSONObject = JSONObject()
) {
    var selectedFields by remember(currentIndex) {
        mutableStateOf(requestedFields.filter { it.required() }.map { it.path() })
    }
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val supportsSelectiveDisclosure = selectedCredential.selectiveDisclosable()
    val hasMoreCredentials = currentIndex + 1 < totalCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        if (totalCount > 1) {
            Text(
                text = "Credential ${currentIndex + 1} of $totalCount",
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = ColorStone600,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        Text(
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = Color.Blue)) { append("Verifier") }
                append(" is requesting access to the following information")
            },
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            textAlign = TextAlign.Center
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .weight(weight = 1f, fill = false)
        ) {
            if (requestedFields.isEmpty() && !supportsSelectiveDisclosure) {
                allClaims.keys().asSequence().toList().sorted().forEach { claimName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enabled = false, checked = true, onCheckedChange = { })
                        Text(
                            buildAnnotatedString {
                                withStyle(style = paragraphStyle) {
                                    append("\t\t")
                                    append(claimName.splitCamelCase().removeUnderscores())
                                }
                            },
                        )
                    }
                }
            } else {
                requestedFields.forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            enabled = supportsSelectiveDisclosure && !it.required(),
                            checked = selectedFields.contains(it.path()) || it.required() || !supportsSelectiveDisclosure,
                            onCheckedChange = { value ->
                                selectedFields = if (!value) {
                                    selectedFields.minus(it.path())
                                } else {
                                    selectedFields.plus(it.path())
                                }
                            }
                        )
                        Text(
                            buildAnnotatedString {
                                withStyle(style = paragraphStyle) {
                                    append("\t\t")
                                    append((it.name() ?: it.inputDescriptorId()).splitCamelCase().removeUnderscores())
                                }
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ColorStone300, RoundedCornerShape(6.dp))
                    .weight(1f)
            ) {
                Text("Cancel", fontFamily = Inter, fontWeight = FontWeight.SemiBold, color = ColorStone950)
            }

            Button(
                onClick = { onContinue(selectedFields) },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorEmerald900),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorEmerald900, RoundedCornerShape(6.dp))
                    .weight(1f)
            ) {
                Text(
                    text = if (hasMoreCredentials) "Next" else "Approve",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun Draft18CredentialSelector(
    requirements: List<Draft18CredentialRequirement>,
    credentialClaims: Map<String, JSONObject>,
    getRequestedFields: (Draft18PresentableCredential) -> List<Draft18RequestedField>,
    onContinue: (List<Draft18PresentableCredential>) -> Unit,
    onCancel: () -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val selectedByRequirement =
        remember { mutableStateOf<Map<Int, Draft18PresentableCredential>>(emptyMap()) }

    val currentRequirement = requirements[currentIndex]
    val hasMoreRequirements = currentIndex + 1 < requirements.size

    fun selectCredential(credential: Draft18PresentableCredential) {
        val credentialId = credential.asParsedCredential().id()
        val current = selectedByRequirement.value[currentIndex]
        selectedByRequirement.value = if (current != null && current.asParsedCredential().id() == credentialId) {
            selectedByRequirement.value - currentIndex
        } else {
            selectedByRequirement.value + (currentIndex to credential)
        }
    }

    fun isSelected(credential: Draft18PresentableCredential): Boolean {
        return selectedByRequirement.value[currentIndex]?.asParsedCredential()?.id() ==
            credential.asParsedCredential().id()
    }

    fun getCredentialTitle(credential: Draft18PresentableCredential): String {
        val parsedCredential = credential.asParsedCredential()

        try {
            credentialClaims[parsedCredential.id()]?.getString("name")
                ?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
        }

        try {
            credentialClaims[parsedCredential.id()]?.getJSONArray("type")?.let {
                for (i in 0 until it.length()) {
                    if (it.get(i).toString() != "VerifiableCredential") {
                        return it.get(i).toString().splitCamelCase()
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            parsedCredential.asMsoMdoc()?.let { return credentialTypeDisplayName(it.doctype()) }
        } catch (_: Exception) {
        }

        try {
            parsedCredential.asDcSdJwt()?.let { return credentialTypeDisplayName(it.vct()) }
        } catch (_: Exception) {
        }

        return currentRequirement.displayName
    }

    fun getSelectedCredentials(): List<Draft18PresentableCredential> {
        return requirements.indices.mapNotNull { selectedByRequirement.value[it] }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        if (requirements.size > 1) {
            Text(
                text = "Requirement ${currentIndex + 1} of ${requirements.size}",
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = ColorStone600,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Select a credential for",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone600
            )
            Text(
                text = currentRequirement.displayName,
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = ColorBlue600
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .weight(weight = 1f, fill = false)
                .padding(top = 12.dp)
        ) {
            currentRequirement.credentials.forEach { credential ->
                Draft18CredentialSelectorItem(
                    credential = credential,
                    requestedFields = getRequestedFields(credential),
                    getCredentialTitle = { getCredentialTitle(it) },
                    isChecked = isSelected(credential),
                    onCheckedChange = { selectCredential(credential) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ColorStone300, RoundedCornerShape(6.dp))
                    .weight(1f)
            ) {
                Text("Cancel", fontFamily = Inter, fontWeight = FontWeight.SemiBold, color = ColorStone950)
            }

            Button(
                onClick = {
                    if (hasMoreRequirements) {
                        currentIndex += 1
                    } else {
                        onContinue(getSelectedCredentials())
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorStone600),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorStone600, RoundedCornerShape(6.dp))
                    .weight(1f)
            ) {
                Text(
                    text = if (hasMoreRequirements) "Next" else "Continue",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun Draft18CredentialSelectorItem(
    credential: Draft18PresentableCredential,
    requestedFields: List<Draft18RequestedField>,
    getCredentialTitle: (Draft18PresentableCredential) -> String,
    isChecked: Boolean,
    onCheckedChange: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val bullet = "\u2022"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val displayFields = requestedFields.map { (it.name() ?: it.inputDescriptorId()).splitCamelCase().removeUnderscores() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, ColorBase300, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onCheckedChange() },
                colors = CheckboxDefaults.colors(
                    checkedColor = ColorBlue600,
                    uncheckedColor = ColorStone300
                )
            )
            Text(
                text = getCredentialTitle(credential),
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = ColorStone950,
                modifier = Modifier.weight(1f)
            )
            if (expanded) {
                Image(
                    painter = painterResource(id = R.drawable.collapse),
                    contentDescription = stringResource(id = R.string.collapse),
                    modifier = Modifier.clickable { expanded = false }
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.expand),
                    contentDescription = stringResource(id = R.string.expand),
                    modifier = Modifier.clickable { expanded = true }
                )
            }
        }

        if (expanded) {
            Text(
                buildAnnotatedString {
                    displayFields.forEach {
                        withStyle(style = paragraphStyle) {
                            append(bullet)
                            append("\t\t")
                            append(it)
                        }
                    }
                },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
