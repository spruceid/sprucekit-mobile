package com.spruceid.mobilesdkexample.wallet

import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.StepResult
import com.spruceid.mobile.sdk.rs.VcalmException
import com.spruceid.mobile.sdk.rs.VcalmHolder
import com.spruceid.mobile.sdk.rs.VcalmMatchedCredentials
import com.spruceid.mobile.sdk.rs.VcalmRequestedField
import com.spruceid.mobile.sdk.rs.VdcCollection
import com.spruceid.mobile.sdk.rs.Vpr
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.acceptRawCredentialIntoWallet
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName
import com.spruceid.mobilesdkexample.utils.removeUnderscores
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "HandleVCALMView"

data class VcalmRequirement(
    val queryIndex: UInt,
    val label: String,
    val candidates: List<ParsedCredential>,
    val fields: List<VcalmRequestedField>,
)

// Iterate the UNION of requested queries (`fieldsByQuery`) and matched ones, so a requested query
// with no matching wallet credential still becomes a requirement (with empty candidates) and
// surfaces as "no matching credential" — instead of being silently dropped.
fun buildVcalmRequirements(
    requestedFields: List<VcalmRequestedField>,
    matched: List<VcalmMatchedCredentials>,
): List<VcalmRequirement> {
    val fieldsByQuery = requestedFields.filter { it.path != "type" && it.path != "@context" }
        .groupBy { it.queryIndex }
    val candidatesByQuery =
        matched.associate { it.queryIndex to it.credentials.map { c -> c.credential } }
    val typesByQuery =
        requestedFields.filter { it.path == "type" }.associate { it.queryIndex to it.value }

    val queryIndices = (fieldsByQuery.keys + candidatesByQuery.keys).toSortedSet()

    return queryIndices.map { queryIndex ->
        val fields = fieldsByQuery[queryIndex].orEmpty()
        val purposeLabel = fields.map { it.purpose }.firstOrNull { !it.isNullOrEmpty() }
        val typeLabel = typesByQuery[queryIndex]?.let { vcalmRequirementLabelFromType(it) }
        VcalmRequirement(
            queryIndex = queryIndex,
            label = typeLabel ?: purposeLabel ?: "Credential",
            candidates = candidatesByQuery[queryIndex].orEmpty(),
            fields = fields,
        )
    }
}

// Turn a `type` field's raw value into a short display label, 
// skipping the generic "VerifiableCredential" entry 
fun vcalmRequirementLabelFromType(rawValue: String): String? {
    val trimmed = rawValue.trim()
    val entries = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        try {
            val array = JSONArray(trimmed)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    } else {
        listOf(trimmed)
    }
    return entries.firstOrNull { it.isNotBlank() && it != "VerifiableCredential" }?.splitCamelCase()
}

fun vcalmCredentialTitle(
    parsedCredential: ParsedCredential,
    credentialClaims: Map<String, JSONObject> = emptyMap(),
): String {
    try {
        credentialClaims[parsedCredential.id()]?.getString("name")?.takeIf { it.isNotBlank() }
            ?.let { return it }
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
    Loading, Err, AddToWallet, SelectCredential, SelectFields,
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
    // Field paths the user consents to disclose, keyed by queryIndex
    var fieldSelections by remember { mutableStateOf<Map<UInt, Set<String>>>(emptyMap()) }
    var picks by remember { mutableStateOf<Map<UInt, ParsedCredential>>(emptyMap()) }
    var redirectUrl by remember { mutableStateOf<String?>(null) }
    var pendingWalletCredentials by remember { mutableStateOf<List<String>?>(null) }
    var offerAcceptResult by remember { mutableStateOf<StepResult?>(null) }
    var offerAcceptError by remember { mutableStateOf(false) }
    var domainMismatch by remember { mutableStateOf<VcalmException.DomainChannelMismatch?>(null) }
    var pendingSelection by remember { mutableStateOf<List<ParsedCredential>>(emptyList()) }
    var pendingFieldPaths by remember { mutableStateOf<Map<UInt, List<String>>>(emptyMap()) }

    fun unwrap(originalUrl: String): String {
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
            } catch (_: Exception) {
                // Not a percent-encoded URL - leave as-is
            }
        }

        val i = url.indexOf(marker)
        if (i < 0) return url
        var encoded = url.substring(i + marker.length)
        val q = encoded.indexOf("?")
        if (q >= 0) encoded = encoded.substring(0, q)
        try {
            val decoded = Uri.decode(encoded)
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        } catch (_: Exception) {
            // Not an encoded URL - use existing input
        }
        return url
    }

    lateinit var handleStep: suspend (StepResult) -> Unit

    // Submit presentation after automatically/manually selecting credentials to fit requirements
    suspend fun trySubmitPresentation(
        selected: List<ParsedCredential>,
        selectedFields: Map<UInt, List<String>>,
        allowDomainMismatch: Boolean
    ) {
        val previousState = state
        state = VCALMState.Loading
        try {
            val result = holder!!.submitPresentation(selected, selectedFields, allowDomainMismatch)
            requirements = null
            domainMismatch = null
            handleStep(result)
        } catch (e: VcalmException.DomainChannelMismatch) {
            pendingSelection = selected
            pendingFieldPaths = selectedFields
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
        try {
            val matched = holder!!.matchedCredentials()
            val requestedFields = holder!!.requestedFields()

            Log.d(TAG, "VCALM verifier request domain=${vpr.domain} query=${vpr.query}")

            if (requestedFields.isEmpty()) {
                // No fields requested — this is a DID-authentication-only request
                // Can submit immediately
                trySubmitPresentation(emptyList(), emptyMap(), false)
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
            fieldSelections = built.associate { req ->
                req.queryIndex to req.fields.map { it.path }.toSet()
            }
            // If no requirement has more than one candidate, skip straight to selective disclosure
            state = if (built.all { it.candidates.size <= 1 }) {
                VCALMState.SelectFields
            } else {
                VCALMState.SelectCredential
            }
        } catch (e: Exception) {
            errorTitle = "Error Handling Verifier Request"
            errorDescription = "Couldn't process the verifier's request. Error: ${e.message}"
            state = VCALMState.Err

        }
    }

    handleStep = { result ->
        when (result) {
            is StepResult.Request -> onRequest(result.vpr)
            is StepResult.Offer -> {
                val offered = holder!!.offeredCredentials()
                // The offer itself isn't accepted at the protocol level until 
                // the user taps "Add to Wallet" in AddToWalletView
                offerAcceptResult = null
                offerAcceptError = false
                pendingWalletCredentials = offered.map { it.rawCredential }
                state = VCALMState.AddToWallet
            }

            is StepResult.Redirect -> {
                redirectUrl = result.url
            }

            is StepResult.Complete -> {
                Toast.showSuccess("Shared successfully")
                navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
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
        val reqs = requirements.orEmpty()
        val selected = reqs.mapNotNull { picks[it.queryIndex] }
        val selectedFields = reqs.associate {
            it.queryIndex to fieldSelections[it.queryIndex].orEmpty().toList()
        }
        trySubmitPresentation(selected, selectedFields, false)
    }

    suspend fun acceptOffer() {
        if (offerAcceptResult != null || offerAcceptError) return
        try {
            val result = holder!!.acceptOffer()
            offerAcceptResult = result
            if (result is StepResult.Problem) {
                // Surface the error, don't let AddToWalletView store anything
                offerAcceptError = true
                handleStep(result)
            }
        } catch (e: Exception) {
            offerAcceptError = true
            errorTitle = "Error Accepting Offer"
            errorDescription = "Couldn't accept the offered credential(s). Error: ${e.message}"
            state = VCALMState.Err
        }
    }

    suspend fun declineOffer() {
        state = VCALMState.Loading
        try {
            val result = holder!!.rejectOffer()
            handleStep(result)
        } catch (e: Exception) {
            // Servers may throw 4xx on further POSTs once offer is delivered as terminal step
            // Treat this as exchange ended, and navigate to home screen
            Toast.showSuccess("Offer declined")
            navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
        }
    }

    suspend fun startExchange() {
        try {
            val vdcCollection = VdcCollection(engine = credentialPacksViewModel.storageManager)
            val signer = Signer(DEFAULT_SIGNING_KEY_ID)
            holder = VcalmHolder.newSession(
                vdcCollection, emptyList(), signer, null
            )
            val usableCredentialPacks = credentialPacksViewModel.credentialPacks.value
            val credentials = usableCredentialPacks.flatMap { pack -> pack.list() }
            usableCredentialPacks.forEach { pack ->
                credentialClaims = credentialClaims + pack.findCredentialClaims(emptyList())
            }
            holder!!.provideCredentials(credentials)

            val result = holder!!.startExchange(unwrap(url), null)

            handleStep(result)
        } catch (e: Exception) {
            errorTitle = "Error Starting Exchange"
            errorDescription = "Couldn't start the exchange ${url}. Error: ${e.message}"
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
            Toast.showSuccess("Continue in your browser to finish this exchange.")
            navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
        }
    }

    when (state) {
        VCALMState.Loading -> LoadingView(loadingText = "Loading...")

        VCALMState.Err -> if (errorTitle != null && errorDescription != null) {
            ErrorView(
                errorTitle = errorTitle!!,
                errorDetails = errorDescription!!,
                onClose = { navController.navigate(Screen.HomeScreen.route) { popUpTo(0) } })
        }

        VCALMState.AddToWallet -> pendingWalletCredentials?.let { rawCredentials ->
            AddToWalletView(
                navController = navController,
                rawCredentials = rawCredentials,
                navigateHomeOnSuccess = false,
                onAcceptCredential = { raw ->
                    // Accept the offer at the protocol level idempotently before
                    // storing this one locally.
                    acceptOffer()
                    if (offerAcceptError) {
                        throw IllegalStateException("Offer acceptance failed")
                    }
                    acceptRawCredentialIntoWallet(
                        raw,
                        credentialPacksViewModel,
                        walletActivityLogsViewModel,
                    )
                },
                onSuccess = {
                    pendingWalletCredentials = null
                    if (offerAcceptError) {
                        // Already surfaced via handleStep()
                    } else {
                        when (val result = offerAcceptResult) {
                            null -> coroutineScope.launch { declineOffer() }
                            is StepResult.Complete -> navController.navigate(Screen.HomeScreen.route) {
                                popUpTo(
                                    0
                                )
                            }

                            is StepResult.Problem -> {}
                            // Chained — the exchange isn't done yet, continue to whatever's next.
                            else -> coroutineScope.launch { handleStep(result) }
                        }
                    }
                },
            )
        }

        VCALMState.SelectCredential -> requirements?.let { reqs ->
            VcalmCredentialSelector(
                requirements = reqs,
                picks = picks,
                credentialClaims = credentialClaims,
                onPick = { queryIndex, credential ->
                    picks = picks + (queryIndex to credential)
                },
                onContinue = { state = VCALMState.SelectFields },
                onCancel = { navController.navigate(Screen.HomeScreen.route) { popUpTo(0) } },
            )
        }

        VCALMState.SelectFields -> requirements?.let { reqs ->
            VcalmFieldsSelector(
                requirements = reqs,
                picks = picks,
                credentialClaims = credentialClaims,
                fieldSelections = fieldSelections,
                onFieldToggle = { queryIndex, selected ->
                    fieldSelections = fieldSelections + (queryIndex to selected)
                },
                onSubmit = { coroutineScope.launch { submitPicks() } },
                onCancel = { navController.navigate(Screen.HomeScreen.route) { popUpTo(0) } },
            )
        }

    }

    domainMismatch?.let { mismatch ->
        VcalmDomainMismatchBottomSheet(
            domain = mismatch.domain,
            channel = mismatch.channel,
            onCancel = {
                domainMismatch = null
                errorTitle = "Presentation flow canceled"
                errorDescription =
                    "The selected credentials were not presented due to user cancellation."
                state = VCALMState.Err
            },
            onContinueAnyway = {
                domainMismatch = null
                coroutineScope.launch {
                    trySubmitPresentation(pendingSelection, pendingFieldPaths, true)
                }
            },
        )
    }
}

@Composable
fun VcalmCredentialSelector(
    requirements: List<VcalmRequirement>,
    picks: Map<UInt, ParsedCredential>,
    credentialClaims: Map<String, JSONObject>,
    onPick: (UInt, ParsedCredential) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val currentRequirement = requirements[currentIndex]
    val hasMoreRequirements = currentIndex + 1 < requirements.size
    val currentSelectionValid =
        currentRequirement.candidates.isEmpty() || picks.containsKey(currentRequirement.queryIndex)

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
            modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Select a credential for",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone600,
                textAlign = TextAlign.Center
            )
            Text(
                text = currentRequirement.label,
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = ColorBlue600,
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .weight(weight = 1f, fill = false)
                .padding(top = 12.dp)
        ) {
            if (currentRequirement.candidates.isEmpty()) {
                Text(
                    text = "No matching credential(s)",
                    fontFamily = Inter,
                    color = ColorRose600,
                )
            } else {
                currentRequirement.candidates.forEach { candidate ->
                    VcalmCredentialSelectorItem(
                        candidate = candidate,
                        requestedFields = currentRequirement.fields,
                        credentialClaims = credentialClaims,
                        isChecked = picks[currentRequirement.queryIndex]?.id() == candidate.id(),
                        onCheckedChange = { onPick(currentRequirement.queryIndex, candidate) })
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
                onClick = { onCancel() },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp, color = ColorStone300, shape = RoundedCornerShape(6.dp)
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
                        if (hasMoreRequirements) {
                            currentIndex += 1
                        } else {
                            onContinue()
                        }
                    }
                }, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentSelectionValid) ColorStone600 else Color.Gray
                ), modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (currentSelectionValid) ColorStone600 else Color.Gray,
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
fun VcalmCredentialSelectorItem(
    candidate: ParsedCredential,
    requestedFields: List<VcalmRequestedField>,
    credentialClaims: Map<String, JSONObject>,
    isChecked: Boolean,
    onCheckedChange: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val bullet = "•"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val displayFields = requestedFields.map { it.path.splitCamelCase().removeUnderscores() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(
                width = 1.dp, color = ColorBase300, shape = RoundedCornerShape(8.dp)
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
                colors = CheckboxDefaults.colors(
                    checkedColor = ColorBlue600, uncheckedColor = ColorStone300
                )
            )
            Text(
                text = vcalmCredentialTitle(candidate, credentialClaims),
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = ColorStone950,
                modifier = Modifier.weight(1f)
            )
            if (displayFields.isNotEmpty()) {
                if (expanded) {
                    Image(
                        painter = painterResource(id = R.drawable.collapse),
                        contentDescription = stringResource(id = R.string.collapse),
                        modifier = Modifier.clickable { expanded = false })
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.expand),
                        contentDescription = stringResource(id = R.string.expand),
                        modifier = Modifier.clickable { expanded = true })
                }
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
                }, modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun VcalmFieldsSelector(
    requirements: List<VcalmRequirement>,
    picks: Map<UInt, ParsedCredential>,
    credentialClaims: Map<String, JSONObject>,
    fieldSelections: Map<UInt, Set<String>>,
    onFieldToggle: (UInt, Set<String>) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val currentRequirement = requirements[currentIndex]
    val hasMoreRequirements = currentIndex + 1 < requirements.size
    val currentCredential = picks[currentRequirement.queryIndex]
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        if (requirements.size > 1) {
            Text(
                text = "Credential ${currentIndex + 1} of ${requirements.size}",
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
            if (currentRequirement.fields.isEmpty()) {
                // No specific fields requested, show all claims from the credential
                val allClaims = currentCredential?.let { credentialClaims[it.id()] } ?: JSONObject()
                // @context is not meaningful to show the user
                allClaims.keys().asSequence().toList()
                    .filter { it != "@context" }
                    .sorted()
                    .forEach { claimName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            enabled = false, checked = true, onCheckedChange = { })
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
                // Field toggles propogate to HandleVCALMView
                val selectedFields = fieldSelections[currentRequirement.queryIndex].orEmpty()
                currentRequirement.fields.forEach { field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            enabled = !field.required,
                            checked = selectedFields.contains(field.path) || field.required,
                            onCheckedChange = { v ->
                                val updated = if (!v) {
                                    selectedFields.minus(field.path)
                                } else {
                                    selectedFields.plus(field.path)
                                }
                                onFieldToggle(currentRequirement.queryIndex, updated)
                            })
                        Text(
                            buildAnnotatedString {
                                withStyle(style = paragraphStyle) {
                                    append("\t\t")
                                    append(field.path.splitCamelCase().removeUnderscores())
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
                onClick = { onCancel() },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp, color = ColorStone300, shape = RoundedCornerShape(6.dp)
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
                    if (hasMoreRequirements) {
                        currentIndex += 1
                    } else {
                        onSubmit()
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorEmerald900),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = ColorEmerald900,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .weight(1f)
            ) {
                Text(
                    text = if (hasMoreRequirements) "Next" else "Approve",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun VcalmDomainMismatchBottomSheet(
    domain: String,
    channel: String,
    onCancel: () -> Unit,
    onContinueAnyway: () -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = onCancel,
        title = "Verifier domain mismatch",
        subtitle = "This verifier's request domain ($domain) doesn't match the " + "exchange's channel ($channel). Only continue if you recognize and trust both sites.",
        onCancel = onCancel,
    ) {
        Button(
            onClick = onContinueAnyway,
            colors = ButtonDefaults.buttonColors(
                containerColor = ColorRose900,
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = ColorRose900,
                    shape = RoundedCornerShape(6.dp),
                ),
        ) {
            Text("Continue Anyway")
        }
    }
}
