package com.spruceid.mobilesdkexample.wallet

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.OfferedValidity
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PresentationSigner
import com.spruceid.mobile.sdk.rs.StepResult
import com.spruceid.mobile.sdk.rs.VcalmException
import com.spruceid.mobile.sdk.rs.VcalmHolder
import com.spruceid.mobile.sdk.rs.VcalmMatchedCredentials
import com.spruceid.mobile.sdk.rs.VcalmOfferedCredential
import com.spruceid.mobile.sdk.rs.VcalmRequestedField
import com.spruceid.mobile.sdk.rs.VdcCollection
import com.spruceid.mobile.sdk.rs.Vpr
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.utils.acceptRawCredentialIntoWallet
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import androidx.core.net.toUri

private const val TAG = "HandleVCALMView"

class VCALMSigner(keyId: String?) : PresentationSigner {
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
        val signature = keyManager.signPayload(keyId, payload)
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

/**
 * A single VCALM query requirement: the credential candidates the holder has
 * that satisfy a given query index, ready to be shown to the user for
 * selection. `queryIndex` ties back to `VcalmRequestedField.queryIndex` /
 * `VcalmMatchedCredentials.queryIndex`. `fields` carries the (non-structural)
 * fields the verifier is requesting for this query, so the picker can tell
 * the user whether they're all required or which ones will be shared.
 */
data class VcalmRequirement(
    val queryIndex: UInt,
    val label: String,
    val candidates: List<ParsedCredential>,
    val fields: List<VcalmRequestedField>,
)

/**
 * Iterate the UNION of requested queries (`fieldsByQuery`) and matched ones, so a requested query
 * with no matching wallet credential still becomes a requirement (with empty candidates) and
 * surfaces as "no matching credential" — instead of being silently dropped.
 */
fun buildVcalmRequirements(
    requestedFields: List<VcalmRequestedField>,
    matched: List<VcalmMatchedCredentials>,
): List<VcalmRequirement> {
    val fieldsByQuery = requestedFields
        .filter { it.path != "type" && it.path != "@context" }
        .groupBy { it.queryIndex }
    val candidatesByQuery = matched.associate { it.queryIndex to it.credentials.map { c -> c.credential } }

    val queryIndices = (fieldsByQuery.keys + candidatesByQuery.keys).toSortedSet()

    return queryIndices.map { queryIndex ->
        val fields = fieldsByQuery[queryIndex].orEmpty()
        val purposeLabel = fields.map { it.purpose }.firstOrNull { !it.isNullOrEmpty() }
        VcalmRequirement(
            queryIndex = queryIndex,
            label = purposeLabel ?: "Credential",
            candidates = candidatesByQuery[queryIndex].orEmpty(),
            fields = fields,
        )
    }
}

/**
 * Returns formatted issue date for a credential card, pulled from `validFrom` (VC 2.0),
 * `issuanceDate` VC 1.1 (), or `null` if neither claim is available.
 */
fun vcalmCredentialIssuedDate(
    parsedCredential: ParsedCredential,
    credentialClaims: Map<String, JSONObject>,
): String? {
    val claims = credentialClaims[parsedCredential.id()] ?: return null
    val raw = claims.optString("validFrom").takeIf { it.isNotBlank() }
        ?: claims.optString("issuanceDate").takeIf { it.isNotBlank() }
        ?: return null
    return raw.substringBefore("T")
}

fun vcalmCredentialTitle(
    parsedCredential: ParsedCredential,
    credentialClaims: Map<String, JSONObject> = emptyMap(),
): String {
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

enum class VCALMState {
    Loading,
    Err,
    AddToWallet,
    SelectCredential,
    Offer,
    Success,
}

@Composable
fun HandleVCALMView(
    navController: NavHostController,
    url: String,
) {
    val credentialPacksViewModel: CredentialPacksViewModel = activityHiltViewModel()
    val walletActivityLogsViewModel: WalletActivityLogsViewModel = activityHiltViewModel()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorDescription by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(VCALMState.Loading) }
    var holder by remember { mutableStateOf<VcalmHolder?>(null) }
    var credentialClaims by remember { mutableStateOf(mapOf<String, JSONObject>()) }

    var requirements by remember { mutableStateOf<List<VcalmRequirement>?>(null) }
    var picks by remember { mutableStateOf<Map<UInt, ParsedCredential>>(emptyMap()) }
    var offeredCredentials by remember { mutableStateOf<List<VcalmOfferedCredential>>(emptyList()) }
    var redirectUrl by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var presentedCredentials by remember { mutableStateOf<List<ParsedCredential>?>(null) }
    // These are handed off to AddToWalletView once VCALM exchange is completed
    var pendingWalletCredentials by remember { mutableStateOf<List<String>?>(null) }
    var domainMismatch by remember { mutableStateOf<VcalmException.DomainChannelMismatch?>(null) }
    var pendingSelection by remember { mutableStateOf<List<ParsedCredential>>(emptyList()) }

    fun unwrap(originalUrl: String) : String{
        var url = originalUrl
        val scheme = "interaction:"
        val marker = "/interactions/"

//        Normalize the exchange endpoint into the bare `https` vcapi exchange URL the holder POSTs to
//        1.    CHAPI deep link — the web switchboard wraps the endpoint in the app's
//              `interaction:` scheme, percent-encoded (`interaction:<enc-https-url>`).
//              => Strip the scheme and decode.
//        2.    Playground landing URL — vcplayground.org serves the endpoint inside an
//              HTML page at `https://…/interactions/<url-encoded-exchange-url>?iuv=1`;
//              POSTing to it returns HTML, not vcapi JSON.
//              => Extract the percent-encoded inner URL.

        if (url.startsWith(scheme)) {
            val remainder = url.substring(scheme.length).replaceFirst(Regex("^/+"), "")
            try {
                val decoded = Uri.decode(remainder)
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    url = decoded
                }
            } catch (_: Error) {
                // Not a percent-encoded URL - leave as-is
            }
        }

        val i = url.indexOf(marker)
        if (i<0) return url
        var encoded = url.substring(i + marker.length)
        val q = encoded.indexOf("?")
        if (q >= 0) encoded = encoded.substring(0, q)
        try {
            val decoded = Uri.decode(encoded)
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        } catch (_: Error) {
            // Not an encoded URL - use existing input
        }
        return url
    }

    lateinit var handleStep: suspend (StepResult) -> Unit

    // Submit presentation after automatically/manually selecting credentials to fit requirements
    suspend fun trySubmitPresentation(selected: List<ParsedCredential>, allowDomainMismatch: Boolean) {
        val previousState = state
        state = VCALMState.Loading
        try {
            val result = holder!!.submitPresentation(selected, allowDomainMismatch)
            requirements = null
            domainMismatch = null
            if (result is StepResult.Complete) {
                presentedCredentials = selected
            }
            handleStep(result)
        } catch (e: VcalmException.DomainChannelMismatch) {
            pendingSelection = selected
            domainMismatch = e
            // Fall back to previous screen, allow user to allow domain channel mismatch and continue
            state = previousState
        } catch (e: Exception) {
            errorTitle = "Error Submitting Presentation"
            errorDescription = "Couldn't submit presentation. Error: ${e.message}"
            state = VCALMState.Err
        }
    }

    suspend fun onRequest(vpr: Vpr) {
        val matched = holder!!.matchedCredentials()
        val requestedFields = holder!!.requestedFields()

        Log.d(TAG, "VCALM verifier request domain=${vpr.domain} query=${vpr.query}")

        if (requestedFields.isEmpty()) {
            // No fields requested — this is a DID-authentication-only request
            // Can submit immediately
            trySubmitPresentation(emptyList(), false)
            return
        }

        val built = buildVcalmRequirements(requestedFields, matched)
        if (built.isEmpty() || built.all { it.candidates.isEmpty() }) {
            errorTitle = "No matching credential(s)"
            errorDescription =
                "You don't have a credential in your wallet that satisfies this verifier's request."
            state = VCALMState.Err
            return
        }

        // When there is only one candidate choice for a requirement, auto select it
        picks = built.filter { it.candidates.size == 1 }
            .associate { it.queryIndex to it.candidates.first() }
        requirements = built
        state = VCALMState.SelectCredential
    }

    handleStep = { result ->
        when (result) {
            is StepResult.Request -> onRequest(result.vpr)
            is StepResult.Offer -> {
                offeredCredentials = holder!!.offeredCredentials()
                state = VCALMState.Offer
            }
            is StepResult.Redirect -> {
                redirectUrl = result.url
            }
            is StepResult.Complete -> {
                successMessage = "Successfully shared."
                state = VCALMState.Success
            }
            is StepResult.Problem -> {
                val details = result.details
                errorTitle = "Verifier reported a problem"
                errorDescription = details.title ?: details.detail ?: details.problemType
                state = VCALMState.Err
            }
        }
    }

    suspend fun submitPicks() {
        val selected = requirements.orEmpty().mapNotNull { picks[it.queryIndex] }
        trySubmitPresentation(selected, false)
    }

    suspend fun acceptOffer() {
        state = VCALMState.Loading
        try {
            val rawCredentials = offeredCredentials.map { it.rawCredential }
            val result = holder!!.acceptOffer()
            offeredCredentials = emptyList()

            when (result) {
                is StepResult.Complete -> {
                    // Hand off to AddToWalletView
                    pendingWalletCredentials = rawCredentials
                    state = VCALMState.AddToWallet
                }
                is StepResult.Problem -> {
                    // Surface the error, don't store credentials yet
                    handleStep(result)
                }
                else -> {
                    // The exchange is chained, not complete yet. Store credential locally
                    // with the same shared helper AddToWalletView uses, then continue
                    rawCredentials.forEach { raw ->
                        acceptRawCredentialIntoWallet(
                            raw,
                            credentialPacksViewModel,
                            walletActivityLogsViewModel
                        )
                    }
                    handleStep(result)
                }
            }
        } catch (e: Exception) {
            errorTitle = "Error Accepting Offer"
            errorDescription = "Couldn't accept the offered credential(s). Error: ${e.message}"
            state = VCALMState.Err
        }
    }

    suspend fun declineOffer() {
        state = VCALMState.Loading
        try {
            val result = holder!!.rejectOffer()
            offeredCredentials = emptyList()
            handleStep(result)
        } catch (e: Exception) {
            errorTitle = "Error Declining Offer"
            errorDescription = "Couldn't decline the offered credential(s). Error: ${e.message}"
            state = VCALMState.Err
        }
    }

    suspend fun startExchange() {
        try {
            val vdcCollection = VdcCollection(engine = credentialPacksViewModel.storageManager)
            val signer = VCALMSigner("vcalm_holder_key")
            holder = VcalmHolder.newSession(
                vdcCollection, emptyList(), signer, null, null
            )
            val usableCredentialPacks = credentialPacksViewModel.credentialPacks.value
            val credentials = usableCredentialPacks.flatMap { pack -> pack.list() }
            usableCredentialPacks.forEach { pack ->
                credentialClaims = credentialClaims + pack.findCredentialClaims(
                    listOf("name", "type", "validFrom", "issuanceDate")
                )
            }
            holder!!.provideCredentials(credentials)

            val result = holder!!.startExchange(unwrap(url), null)

            handleStep(result)
        } catch (e: Exception) {
            errorTitle = "Error Adding Credential"
            errorDescription = "Couldn't complete exchange ${url}. Error: ${e.message}"
            state = VCALMState.Err
        }
    }

    LaunchedEffect(Unit) {
        state = VCALMState.Loading
        startExchange()
    }

    // Redirect user to browser, and show corresponding message on app
    LaunchedEffect(redirectUrl) {
        val target = redirectUrl
        if (target != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
            } catch (e: Exception) {
                Log.d(TAG, "Failed to open VCALM redirect URL: ${e.message}")
            }
            successMessage = "Continue in your browser to finish this exchange."
            state = VCALMState.Success
        }
    }

    when (state) {
        VCALMState.Loading -> LoadingView(loadingText = "Loading...")

        VCALMState.Err ->
            if (errorTitle != null && errorDescription != null) {
                ErrorView(
                    errorTitle = errorTitle!!,
                    errorDetails = errorDescription!!,
                    onClose = { navController.navigate(Screen.HomeScreen.route) { popUpTo(0) } })
            }

        VCALMState.AddToWallet ->
            pendingWalletCredentials?.let { rawCredentials ->
                AddToWalletView(
                    navController = navController,
                    rawCredentials = rawCredentials,
                    onSuccess = { pendingWalletCredentials = null },
                )
            }

        VCALMState.SelectCredential ->
            requirements?.let { reqs ->
                VcalmRequirementPicker(
                    requirements = reqs,
                    picks = picks,
                    credentialClaims = credentialClaims,
                    onPick = { queryIndex, credential -> picks = picks + (queryIndex to credential) },
                    onSubmit = { coroutineScope.launch { submitPicks() } },
                )
            }

        VCALMState.Offer ->
            if (offeredCredentials.isNotEmpty()) {
                VcalmOfferView(
                    offered = offeredCredentials,
                    onAccept = { coroutineScope.launch { acceptOffer() } },
                    onDecline = { coroutineScope.launch { declineOffer() } },
                )
            }

        VCALMState.Success ->
            successMessage?.let { message ->
                VcalmSuccessView(
                    message = message,
                    presentedCredentials = presentedCredentials,
                    credentialClaims = credentialClaims,
                    onDone = { navController.navigate(Screen.HomeScreen.route) { popUpTo(0) } },
                )
            }
    }

    domainMismatch?.let { mismatch ->
        VcalmDomainMismatchBottomSheet(
            domain = mismatch.domain,
            channel = mismatch.channel,
            onCancel = { domainMismatch = null },
            onContinueAnyway = {
                domainMismatch = null
                coroutineScope.launch {
                    trySubmitPresentation(pendingSelection, true)
                }
            },
        )
    }
}

@Composable
fun VcalmRequirementPicker(
    requirements: List<VcalmRequirement>,
    picks: Map<UInt, ParsedCredential>,
    credentialClaims: Map<String, JSONObject>,
    onPick: (UInt, ParsedCredential) -> Unit,
    onSubmit: () -> Unit,
) {
    val allResolved = requirements.all { it.candidates.isEmpty() || picks.containsKey(it.queryIndex) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text = "Review Details", fontSize = 20.sp)
        Spacer(modifier = Modifier.height(16.dp))

        requirements.forEach { requirement ->
            key(requirement.queryIndex) {
            Text(text = requirement.label, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))

            val allRequired = requirement.fields.isEmpty() || requirement.fields.all { it.required }
            if (requirement.fields.isNotEmpty()) {
                Text(
                    text = if (allRequired) {
                        "These fields are required by the verifier"
                    } else {
                        "Select which fields to share:"
                    },
                    fontSize = 14.sp,
                    color = ColorStone500,
                )
                // Optional fields are pre-selected but can be unchecked,
                // mandatory fields are always selected but disabled
                var selectedFields by remember(requirement.queryIndex) {
                    mutableStateOf(requirement.fields.map { it.path }.toSet())
                }
                requirement.fields.forEach { field ->
                    val fieldLabel = field.path
                        .splitCamelCase()
                        .removeUnderscores()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = field.required || selectedFields.contains(field.path),
                            enabled = !field.required,
                            onCheckedChange = { checked ->
                                selectedFields = if (checked) {
                                    selectedFields + field.path
                                } else {
                                    selectedFields - field.path
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = ColorBlue600,
                                uncheckedColor = ColorStone300,
                                disabledCheckedColor = ColorStone500,
                            ),
                        )
                        Text(
                            text = "$fieldLabel${if (!field.required) " (optional)" else ""}",
                            fontSize = 12.sp,
                            color = ColorStone500,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

            }

            if (requirement.candidates.isEmpty()) {
                Text(
                    text = "No matching credential(s)",
                    color = ColorRose600,
                )
            } else if (requirement.candidates.size == 1) {
                // Pre-select credential if only one matches
                Text(
                    text = "Using: ${vcalmCredentialTitle(requirement.candidates.first(), credentialClaims)}",
                    fontSize = 14.sp,
                )
            } else {
                requirement.candidates.forEach { candidate ->
                    val selected = picks[requirement.queryIndex]?.id() == candidate.id()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { onPick(requirement.queryIndex, candidate) },
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onPick(requirement.queryIndex, candidate) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = vcalmCredentialTitle(candidate, credentialClaims))
                    }
                }
            }
                if (requirement.fields.isEmpty()) {
                    Text(
                        text ="All fields are required by the verifier",
                        fontSize = 14.sp,
                        color = ColorStone500,
                    )
                }
            Spacer(modifier = Modifier.height(16.dp))
            }
        }
Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onSubmit,
            enabled = allResolved,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
fun VcalmOfferView(
    offered: List<VcalmOfferedCredential>,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(16.dp)) {
        Text(text = "Credential offer", fontSize = 20.sp)
        Spacer(modifier = Modifier.height(16.dp))

        offered.forEach { credential ->
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = credential.types.lastOrNull()
                            ?.let { credentialTypeDisplayName(it) }
                            ?: "Credential",
                        fontSize = 16.sp,
                    )
                    credential.issuer?.let { issuer ->
                        Text(text = "Issuer: $issuer")
                    }
                    // Time bounded credentials will still be stored, so surface
                    // the warning associated with it before user makes a decision.
                    // When the credential's validity is blocking (ex: unverifiable), the
                    // error is surfaced through ErrorView
                    if (credential.validity == OfferedValidity.TIME_BOUNDED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This credential may be premature or expired.",
                            color = ColorStone500
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Button(
                onClick = onAccept,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Accept")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDecline,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Decline")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VcalmDomainMismatchBottomSheet(
    domain: String,
    channel: String,
    onCancel: () -> Unit,
    onContinueAnyway: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Verifier domain mismatch",
                fontSize = 20.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "This verifier's request domain ($domain) doesn't match the " +
                    "exchange's channel ($channel). Only continue if you recognize and trust both sites.",
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onContinueAnyway,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorRose900,
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue Anyway")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun VcalmSuccessView(
    message: String,
    presentedCredentials: List<ParsedCredential>? = null,
    credentialClaims: Map<String, JSONObject> = emptyMap(),
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(text = message, fontSize = 16.sp)

            if (!presentedCredentials.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                presentedCredentials.forEach { credential ->
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = vcalmCredentialTitle(credential, credentialClaims),
                                fontSize = 16.sp,
                            )
                            Text(
                                text = "ID: ${credential.id()}",
                                fontSize = 12.sp,
                                color = ColorStone500,
                            )
                            vcalmCredentialIssuedDate(credential, credentialClaims)?.let { issuedDate ->
                                Text(
                                    text = "Valid from: $issuedDate",
                                    fontSize = 12.sp,
                                    color = ColorStone500,
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = onDone,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}
