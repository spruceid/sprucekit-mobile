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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.Holder
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PermissionRequest
import com.spruceid.mobile.sdk.rs.PermissionResponse
import com.spruceid.mobile.sdk.rs.PresentableCredential
import com.spruceid.mobile.sdk.rs.PresentationSigner
import com.spruceid.mobile.sdk.rs.RequestedField
import com.spruceid.mobile.sdk.rs.ResponseOptions
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.utils.trustedDids
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class Signer(keyId: String?) : PresentationSigner {
    private val keyId = keyId ?: DEFAULT_SIGNING_KEY_ID
    private val keyManager = KeyManager()
    private var jwk: String
    private val didJwk = DidMethodUtils(DidMethod.JWK)

    init {
        if (!keyManager.keyExists(this.keyId)) {
            keyManager.generateSigningKey(id = this.keyId)
        }
        this.jwk = keyManager.getJwk(this.keyId) ?: throw IllegalArgumentException("Invalid kid")
    }

    override suspend fun sign(payload: ByteArray): ByteArray {
        val signature =
            keyManager.signPayload(keyId, payload)
                ?: throw IllegalStateException("Failed to sign payload")

        return signature
    }

    override fun algorithm(): String {
        // Parse the jwk as a JSON object and return the "alg" field
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
        // TODO: Add an uniffi enum type for crypto suites.
        return "ecdsa-rdfc-2019"
    }
}

enum class OID4VPState {
    Err,
    SelectCredential,
    SelectiveDisclosure,
    Loading,
    None,
}

class OID4VPError {
    var title: String
    var details: String

    constructor(title: String, details: String) {
        this.title = title
        this.details = details
    }
}

@Composable
fun HandleOID4VPView(
    navController: NavController,
    url: String,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel
) {
    val scope = rememberCoroutineScope()
    val credentialPacks = credentialPacksViewModel.credentialPacks

    var credentialClaims by remember { mutableStateOf(mapOf<String, JSONObject>()) }
    var holder by remember { mutableStateOf<Holder?>(null) }
    var permissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var permissionResponse by remember { mutableStateOf<PermissionResponse?>(null) }
    var selectedCredential by remember { mutableStateOf<PresentableCredential?>(null) }
    var lSelectedCredentials = remember { mutableStateOf<List<PresentableCredential>>(listOf()) }
    var state by remember { mutableStateOf(OID4VPState.None) }
    var error by remember { mutableStateOf<OID4VPError?>(null) }
    val ctx = LocalContext.current

    fun onBack() {
        navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
    }

    when (state) {
        OID4VPState.None -> LaunchedEffect(Unit) {
            try {
                val credentials = mutableListOf<ParsedCredential>()
                credentialPacks.value.forEach { credentialPack ->
                    credentials.addAll(credentialPack.list())
                    credentialClaims += credentialPack.findCredentialClaims(listOf("name", "type"))
                }

                withContext(Dispatchers.IO) {
                    val signer = Signer(DEFAULT_SIGNING_KEY_ID)
                    holder =
                        Holder.newWithCredentials(
                            credentials,
                            trustedDids,
                            signer,
                            getVCPlaygroundOID4VCIContext(ctx)
                        )
                    val newurl = url.replace("authorize", "")
                    val tempPermissionRequest = holder!!.authorizationRequest(newurl)
                    val permissionRequestCredentials = tempPermissionRequest.credentials()

                    permissionRequest = tempPermissionRequest
                    if (permissionRequestCredentials.isNotEmpty()) {
                        if (permissionRequestCredentials.count() == 1) {
                            lSelectedCredentials.value = permissionRequestCredentials
                            selectedCredential = permissionRequestCredentials.first()
                            state = OID4VPState.SelectiveDisclosure
                        } else {
                            state = OID4VPState.SelectCredential
                        }
                    } else {
                        error = OID4VPError(
                            "No matching credential(s)",
                            "There are no credentials in your wallet that match the verification request you have scanned",
                        )
                        state = OID4VPState.Err
                    }
                }
            } catch (e: Exception) {
                error = OID4VPError("No matching credential(s)", e.localizedMessage!!)
                state = OID4VPState.Err
            }
        }

        OID4VPState.Err ->
            ErrorView(
                errorTitle = error!!.title,
                errorDetails = error!!.details,
                onClose = { onBack() }
            )

        OID4VPState.SelectCredential -> CredentialSelector(
            credentials = permissionRequest!!.credentials(),
            credentialClaims = credentialClaims,
            getRequestedFields = { credential ->
                permissionRequest!!.requestedFields(credential)
            },
            onContinue = { selectedCredentials ->
                scope.launch {
                    try {
                        // TODO: support multiple presentation
                        lSelectedCredentials.value = selectedCredentials
                        selectedCredential = selectedCredentials.first()
                        state = OID4VPState.SelectiveDisclosure
                    } catch (e: Exception) {
                        error = OID4VPError("Failed to select credential", e.localizedMessage!!)
                        state = OID4VPState.Err
                    }
                }
            },
            onCancel = { onBack() }
        )

        OID4VPState.SelectiveDisclosure -> DataFieldSelector(
            requestedFields =
                permissionRequest!!.requestedFields(selectedCredential!!),
            onContinue = {
                scope.launch {
                    try {
                        permissionResponse =
                            permissionRequest!!.createPermissionResponse(
                                lSelectedCredentials.value,
                                it,
                                ResponseOptions(false, false, false)
                            )
                        holder!!.submitPermissionResponse(permissionResponse!!)
                        val credentialPack =
                            credentialPacks.value.firstOrNull { credentialPack ->
                                credentialPack.getCredentialById(
                                    selectedCredential!!.asParsedCredential().id()
                                ) != null
                            }!!
                        val credentialInfo =
                            getCredentialIdTitleAndIssuer(credentialPack)
                        walletActivityLogsViewModel.saveWalletActivityLog(
                            walletActivityLogs = WalletActivityLogs(
                                credentialPackId = credentialPack.id().toString(),
                                credentialId = credentialInfo.first,
                                credentialTitle = credentialInfo.second,
                                issuer = credentialInfo.third,
                                action = "Verification",
                                dateTime = getCurrentSqlDate(),
                                additionalInformation = ""
                            )
                        )
                        Toast.showSuccess("Shared successfully")
                        onBack()
                    } catch (e: Exception) {
                        error =
                            OID4VPError("Failed to selective disclose fields", e.localizedMessage!!)
                        state = OID4VPState.Err
                    }
                }
            },
            onCancel = { onBack() },
            selectedCredential = selectedCredential!!
        )

        OID4VPState.Loading ->
            LoadingView(loadingText = "Loading...")
    }
}

@Composable
fun DataFieldSelector(
    selectedCredential: PresentableCredential,
    requestedFields: List<RequestedField>,
    onContinue: (selectedFields: List<List<String>>) -> Unit,
    onCancel: () -> Unit
) {
    var selectedFields by remember {
        mutableStateOf(requestedFields.filter { it.required() }.map { it.path() }.toList())
    }
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .weight(weight = 1f, fill = false)
        ) {
            requestedFields.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        enabled = selectedCredential.selectiveDisclosable() && !it.required(),
                        checked = selectedFields.contains(it.path()) || it.required(),
                        onCheckedChange = { v ->
                            selectedFields = if (!v) {
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
                                append(it.name()?.replaceFirstChar(Char::titlecase) ?: "")
                            }
                        },
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onCancel() },
                shape = RoundedCornerShape(6.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = ColorStone950,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = ColorStone300,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .weight(1f)
            ) {
                Text(
                    text = "Cancel",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorStone950,
                )
            }

            Button(
                onClick = { onContinue(listOf(selectedFields)) },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorEmerald900),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color = ColorEmerald900,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .weight(1f)
            ) {
                Text(
                    text = "Approve",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun CredentialSelector(
    credentials: List<PresentableCredential>,
    credentialClaims: Map<String, JSONObject>,
    getRequestedFields: (PresentableCredential) -> List<RequestedField>,
    onContinue: (List<PresentableCredential>) -> Unit,
    onCancel: () -> Unit,
    allowMultiple: Boolean = false
) {
    val selectedCredentials = remember { mutableStateListOf<PresentableCredential>() }

    fun selectCredential(credential: PresentableCredential) {
        if (allowMultiple) {
            selectedCredentials.add(credential)
        } else {
            selectedCredentials.clear()
            selectedCredentials.add(credential)
        }
    }

    fun removeCredential(credential: PresentableCredential) {
        selectedCredentials.remove(credential)
    }

    fun getCredentialTitle(credential: PresentableCredential): String {
        try {
            credentialClaims[credential.asParsedCredential().id()]?.getString("name").let {
                return it.toString()
            }
        } catch (_: Exception) {
        }

        try {
            credentialClaims[credential.asParsedCredential().id()]?.getJSONArray("type").let {
                for (i in 0 until it!!.length()) {
                    if (it.get(i).toString() != "VerifiableCredential") {
                        return it.get(i).toString()
                    }
                }
                return ""
            }
        } catch (_: Exception) {
        }
        return ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        Text(
            text = "Select the credential${if (allowMultiple) "(s)" else ""} to share",
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            textAlign = TextAlign.Center
        )

        if (allowMultiple) {
            Text(
                text = "Select All",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                color = ColorBlue600,
                modifier =
                    Modifier.clickable {
                        // TODO: implement select all
                    }
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .weight(weight = 1f, fill = false)
        ) {
            credentials.forEach { credential ->
                CredentialSelectorItem(
                    credential = credential,
                    requestedFields = getRequestedFields(credential),
                    getCredentialTitle = { cred -> getCredentialTitle(cred) },
                    isChecked = credential in selectedCredentials,
                    selectCredential = { cred -> selectCredential(cred) },
                    removeCredential = { cred -> removeCredential(cred) },
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onCancel() },
                shape = RoundedCornerShape(6.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = ColorStone950,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = ColorStone300,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .weight(1f)
            ) {
                Text(
                    text = "Cancel",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorStone950,
                )
            }

            Button(
                onClick = {
                    if (selectedCredentials.isNotEmpty()) {
                        onContinue(selectedCredentials)
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            if (selectedCredentials.isNotEmpty()) {
                                ColorStone600
                            } else {
                                Color.Gray
                            }
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color =
                                if (selectedCredentials.isNotEmpty()) {
                                    ColorStone600
                                } else {
                                    Color.Gray
                                },
                            shape = RoundedCornerShape(6.dp),
                        )
                        .weight(1f)
            ) {
                Text(
                    text = "Continue",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun CredentialSelectorItem(
    credential: PresentableCredential,
    requestedFields: List<RequestedField>,
    getCredentialTitle: (PresentableCredential) -> String,
    isChecked: Boolean,
    selectCredential: (PresentableCredential) -> Unit,
    removeCredential: (PresentableCredential) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val bullet = "\u2022"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val mockDataField =
        requestedFields.map { field -> field.name()?.replaceFirstChar(Char::titlecase) ?: "" }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .border(
                    width = 1.dp,
                    color = ColorBase300,
                    shape = RoundedCornerShape(8.dp)
                )
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
                onCheckedChange = { isChecked ->
                    if (isChecked) {
                        selectCredential(credential)
                    } else {
                        removeCredential(credential)
                    }
                },
                colors =
                    CheckboxDefaults.colors(
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
                    mockDataField.forEach {
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
