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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.Oid4vpVersion
import com.spruceid.mobile.sdk.rs.Oid4vpHolder
import com.spruceid.mobile.sdk.rs.Oid4vpPermissionResponse
import com.spruceid.mobile.sdk.rs.Oid4vpPresentableCredential
import com.spruceid.mobile.sdk.rs.Oid4vpPresentationSigner
import com.spruceid.mobile.sdk.rs.Oid4vpRequestedField
import com.spruceid.mobile.sdk.rs.Oid4vpRequirement
import com.spruceid.mobile.sdk.rs.Oid4vpResponseOptions
import com.spruceid.mobile.sdk.rs.Oid4vpSession
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.getOid4vpVersion
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
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.utils.trustedDids
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class Signer(keyId: String?) : Oid4vpPresentationSigner {
    private val keyId = keyId ?: DEFAULT_SIGNING_KEY_ID
    private val keyManager = KeyManager()
    private var jwk: String
    private val didJwk = DidMethodUtils(DidMethod.JWK)

    init {
        if (!keyManager.keyExists(this.keyId)) {
            keyManager.generateSigningKey(id = this.keyId)
        }
        this.jwk = keyManager.getJwk(this.keyId)?.toString() ?: throw IllegalArgumentException("Invalid kid")
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
    credentialPackId: String?
) {
    val credentialPacksViewModel: CredentialPacksViewModel = activityHiltViewModel()
    val walletActivityLogsViewModel: WalletActivityLogsViewModel = activityHiltViewModel()
    val scope = rememberCoroutineScope()
    val credentialPacks by credentialPacksViewModel.credentialPacks.collectAsState()

    var credentialClaims by remember { mutableStateOf(mapOf<String, JSONObject>()) }
    var requestVersion by remember { mutableStateOf<Oid4vpVersion?>(null) }
    var holder by remember { mutableStateOf<Oid4vpHolder?>(null) }
    var session by remember { mutableStateOf<Oid4vpSession?>(null) }
    var permissionResponse by remember { mutableStateOf<Oid4vpPermissionResponse?>(null) }
    val lSelectedCredentials = remember { mutableStateOf<List<Oid4vpPresentableCredential>>(listOf()) }
    var state by remember { mutableStateOf(OID4VPState.None) }
    var error by remember { mutableStateOf<OID4VPError?>(null) }
    val ctx = LocalContext.current

    // Track selective disclosure progress for multiple credentials
    var currentDisclosureIndex by remember { mutableIntStateOf(0) }
    var allSelectedFields by remember { mutableStateOf<List<List<String>>>(listOf()) }

    fun onBack() {
        navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
    }

    fun credentialTitle(parsedCredential: ParsedCredential): String {
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

        return "Credential"
    }

    when (state) {
        OID4VPState.None -> LaunchedEffect(Unit) {
            try {
                val usableCredentialPacks: List<CredentialPack> = credentialPackId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { id -> credentialPacksViewModel.getById(id) }
                    ?.let { listOf(it) }
                    ?: credentialPacks

                val credentials = mutableListOf<ParsedCredential>()
                usableCredentialPacks.forEach { credentialPack ->
                    credentials.addAll(credentialPack.list())
                    credentialClaims += credentialPack.findCredentialClaims(listOf("name", "type"))
                }

                withContext(Dispatchers.IO) {
                    val newurl = url.replace("authorize", "")
                    requestVersion = getOid4vpVersion(newurl)

                    when (requestVersion) {
                        Oid4vpVersion.UNSUPPORTED, null -> {
                            error = OID4VPError(
                                "Unsupported request",
                                "Unable to determine whether the request is OID4VP v1.0 or draft 18.",
                            )
                            state = OID4VPState.Err
                        }

                        else -> {
                            val signer = Signer(DEFAULT_SIGNING_KEY_ID)
                            holder =
                                Oid4vpHolder.newWithCredentials(
                                    credentials,
                                    trustedDids,
                                    signer,
                                    getVCPlaygroundOID4VCIContext(ctx),
                                    KeyManager()
                                )
                            val tempSession = holder!!.start(newurl)
                            val sessionCredentials = tempSession.credentials()

                            session = tempSession
                            if (sessionCredentials.isNotEmpty()) {
                                val requirements = tempSession.requirements()
                                val canSkipSelection =
                                    requirements.size == 1 && requirements.first().credentials.size == 1
                                if (canSkipSelection) {
                                    lSelectedCredentials.value = sessionCredentials
                                    currentDisclosureIndex = 0
                                    allSelectedFields = listOf()
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

        OID4VPState.SelectCredential ->
            CredentialSelector(
                requirements = session!!.requirements(),
                credentialClaims = credentialClaims,
                getRequestedFields = { credential ->
                    session!!.requestedFields(credential)
                },
                onContinue = { selectedCredentials ->
                    scope.launch {
                        try {
                            lSelectedCredentials.value = selectedCredentials
                            currentDisclosureIndex = 0
                            allSelectedFields = listOf()
                            state = OID4VPState.SelectiveDisclosure
                        } catch (e: Exception) {
                            error = OID4VPError("Failed to select credential", e.localizedMessage!!)
                            state = OID4VPState.Err
                        }
                    }
                },
                onCancel = { onBack() }
            )

        OID4VPState.SelectiveDisclosure -> {
            val currentCredential = lSelectedCredentials.value[currentDisclosureIndex]
            val totalCredentials = lSelectedCredentials.value.size

            val currentCredentialPack = credentialPacks.firstOrNull { pack ->
                pack.getCredentialById(currentCredential.asParsedCredential().id()) != null
            }
            val currentAllClaims = currentCredentialPack?.getCredentialClaims(
                currentCredential.asParsedCredential(),
                listOf()
            ) ?: JSONObject()

            DataFieldSelector(
                requestedFields = session!!.requestedFields(currentCredential),
                selectedCredential = currentCredential,
                currentIndex = currentDisclosureIndex,
                totalCount = totalCredentials,
                onContinue = { selectedFieldsForCredential ->
                    allSelectedFields = allSelectedFields + listOf(selectedFieldsForCredential)

                    if (currentDisclosureIndex + 1 < totalCredentials) {
                        currentDisclosureIndex += 1
                        state = OID4VPState.Loading
                        scope.launch {
                            state = OID4VPState.SelectiveDisclosure
                        }
                    } else {
                        scope.launch {
                            try {
                                permissionResponse =
                                    session!!.createPermissionResponse(
                                        lSelectedCredentials.value,
                                        allSelectedFields,
                                        Oid4vpResponseOptions(
                                            forceArraySerialization = false,
                                            shouldStripQuotes = false,
                                            removeVpPathPrefix = false
                                        )
                                    )
                                session!!.submitPermissionResponse(permissionResponse!!)

                                for (credential in lSelectedCredentials.value) {
                                    val credentialPack =
                                        credentialPacks.firstOrNull { pack ->
                                            pack.getCredentialById(
                                                credential.asParsedCredential().id()
                                            ) != null
                                        }
                                    if (credentialPack != null) {
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
                                    }
                                }

                                Toast.showSuccess("Shared successfully")
                                onBack()
                            } catch (e: Exception) {
                                error =
                                    OID4VPError(
                                        "Failed to submit presentation",
                                        e.localizedMessage!!
                                    )
                                state = OID4VPState.Err
                            }
                        }
                    }
                },
                onCancel = { onBack() },
                allClaims = currentAllClaims
            )
        }

        OID4VPState.Loading ->
            LoadingView(loadingText = "Loading...")
    }
}

@Composable
fun DataFieldSelector(
    selectedCredential: Oid4vpPresentableCredential,
    requestedFields: List<Oid4vpRequestedField>,
    currentIndex: Int = 0,
    totalCount: Int = 1,
    onContinue: (selectedFields: List<String>) -> Unit,
    onCancel: () -> Unit,
    allClaims: JSONObject = JSONObject()
) {
    var selectedFields by remember(currentIndex) {
        mutableStateOf(requestedFields.filter { it.required }.map { it.path }.toList())
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
        // Progress indicator for multi-credential flow
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .weight(weight = 1f, fill = false)
        ) {
            if (requestedFields.isEmpty() && !supportsSelectiveDisclosure) {
                // No specific fields requested, show all claims from the credential
                allClaims.keys().asSequence().toList().sorted().forEach { claimName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            enabled = false,
                            checked = true,
                            onCheckedChange = { }
                        )
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
                            enabled = supportsSelectiveDisclosure && !it.required,
                            checked = selectedFields.contains(it.path) || it.required || !supportsSelectiveDisclosure,
                            onCheckedChange = { v ->
                                selectedFields = if (!v) {
                                    selectedFields.minus(it.path)
                                } else {
                                    selectedFields.plus(it.path)
                                }
                            }
                        )
                        Text(
                            buildAnnotatedString {
                                withStyle(style = paragraphStyle) {
                                    append("\t\t")
                                    append((it.name ?: "").splitCamelCase().removeUnderscores())
                                }
                            },
                        )
                    }
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
                onClick = { onContinue(selectedFields) },
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
fun CredentialSelector(
    requirements: List<Oid4vpRequirement>,
    credentialClaims: Map<String, JSONObject>,
    getRequestedFields: (Oid4vpPresentableCredential) -> List<Oid4vpRequestedField>,
    onContinue: (List<Oid4vpPresentableCredential>) -> Unit,
    onCancel: () -> Unit
) {
    // Track current requirement index (step-by-step flow)
    var currentIndex by remember { mutableIntStateOf(0) }
    // Track selected credential per requirement (by index)
    val selectedByRequirement =
        remember { mutableStateOf<Map<Int, Oid4vpPresentableCredential>>(emptyMap()) }

    val currentRequirement = requirements[currentIndex]
    val hasMoreRequirements = currentIndex + 1 < requirements.size
    val currentSelectionValid =
        !currentRequirement.required || selectedByRequirement.value.containsKey(currentIndex)

    fun selectCredential(credential: Oid4vpPresentableCredential) {
        val credId = credential.asParsedCredential().id()
        val current = selectedByRequirement.value[currentIndex]
        if (current != null && current.asParsedCredential().id() == credId) {
            // Deselect if tapping the same credential
            selectedByRequirement.value = selectedByRequirement.value - currentIndex
        } else {
            // Select this credential for this requirement
            selectedByRequirement.value = selectedByRequirement.value + (currentIndex to credential)
        }
    }

    fun isSelected(credential: Oid4vpPresentableCredential): Boolean {
        val selected = selectedByRequirement.value[currentIndex] ?: return false
        return selected.asParsedCredential().id() == credential.asParsedCredential().id()
    }

    fun getCredentialTitle(credential: Oid4vpPresentableCredential): String {
        try {
            credentialClaims[credential.asParsedCredential().id()]?.getString("name")
                ?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
        }

        try {
            credentialClaims[credential.asParsedCredential().id()]?.getJSONArray("type")?.let {
                for (i in 0 until it.length()) {
                    if (it.get(i).toString() != "VerifiableCredential") {
                        return it.get(i).toString().splitCamelCase()
                    }
                }
            }
        } catch (_: Exception) {
        }

        // For mdocs, use doctype for display name
        try {
            credential.asParsedCredential().asMsoMdoc()?.let { mdoc ->
                return credentialTypeDisplayName(mdoc.doctype())
            }
        } catch (_: Exception) {
        }

        // For dc+sd-jwt, use vct for display name
        try {
            credential.asParsedCredential().asDcSdJwt()?.let { dcSdJwt ->
                return credentialTypeDisplayName(dcSdJwt.vct())
            }
        } catch (_: Exception) {
        }

        return ""
    }

    fun getSelectedCredentials(): List<Oid4vpPresentableCredential> {
        return requirements.indices.mapNotNull { index ->
            selectedByRequirement.value[index]
        }
    }

    fun goToNextOrFinish() {
        if (hasMoreRequirements) {
            currentIndex += 1
        } else {
            onContinue(getSelectedCredentials())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        // Progress indicator
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

        // Header with requirement name
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = currentRequirement.displayName,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = ColorBlue600
                )
                if (!currentRequirement.required) {
                    Text(
                        text = " (Optional)",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = ColorStone600
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .weight(weight = 1f, fill = false)
                    .padding(top = 12.dp)
        ) {
            currentRequirement.credentials.forEach { credential ->
                CredentialSelectorItem(
                    credential = credential,
                    requestedFields = getRequestedFields(credential),
                    getCredentialTitle = { cred -> getCredentialTitle(cred) },
                    isChecked = isSelected(credential),
                    onCheckedChange = { selectCredential(credential) }
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
                    if (currentSelectionValid) {
                        goToNextOrFinish()
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            if (currentSelectionValid) {
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
                                if (currentSelectionValid) {
                                    ColorStone600
                                } else {
                                    Color.Gray
                                },
                            shape = RoundedCornerShape(6.dp),
                        )
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
fun CredentialSelectorItem(
    credential: Oid4vpPresentableCredential,
    requestedFields: List<Oid4vpRequestedField>,
    getCredentialTitle: (Oid4vpPresentableCredential) -> String,
    isChecked: Boolean,
    onCheckedChange: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val bullet = "\u2022"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val displayFields =
        requestedFields.map { field -> (field.name ?: "").splitCamelCase().removeUnderscores() }

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
                onCheckedChange = { onCheckedChange() },
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
