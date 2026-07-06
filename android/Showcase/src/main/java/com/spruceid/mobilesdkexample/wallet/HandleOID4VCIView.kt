package com.spruceid.mobilesdkexample.wallet

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.Oid4vciAsyncHttpClient
import com.spruceid.mobile.sdk.rs.CredentialResponse
import com.spruceid.mobile.sdk.rs.CredentialToken
import com.spruceid.mobile.sdk.rs.CredentialTokenState
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.JwsSigner
import com.spruceid.mobile.sdk.rs.JwsSignerInfo
import com.spruceid.mobile.sdk.rs.Oid4vciClient
import com.spruceid.mobile.sdk.rs.Proofs
import com.spruceid.mobile.sdk.rs.TxCodeRequired
import com.spruceid.mobile.sdk.rs.createJwtProof
import com.spruceid.mobile.sdk.rs.decodeDerSignature
import com.spruceid.mobile.sdk.rs.generateDidJwkUrl
import com.spruceid.mobile.sdk.rs.verifyRawCredential
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.navigation.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun HandleOID4VCIView(
    navController: NavHostController,
    url: String
) {
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var credential by remember { mutableStateOf<String?>(null) }
    // The fresh per-credential key alias generated for this issuance, carried
    // to AddToWalletView so it is stored as the credential's key_alias.
    var credentialKeyAlias by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback =
        navController.currentBackStackEntry?.savedStateHandle?.get<suspend () -> Unit>("callback")

    var promptForPin by remember { mutableStateOf(false) }
    var pendingTxCodeState by remember { mutableStateOf<TxCodeRequired?>(null) }
    var pinInput by remember { mutableStateOf("") }

    // Hoisted so the PIN-submit callback can reach them after LaunchedEffect completes.
    var hoistedHttpClient by remember { mutableStateOf<Oid4vciAsyncHttpClient?>(null) }
    var hoistedOid4vciClient by remember { mutableStateOf<Oid4vciClient?>(null) }
    var hoistedClientId by remember { mutableStateOf<String?>(null) }
    var hoistedCredentialIssuer by remember { mutableStateOf<String?>(null) }
    var hoistedSigner by remember { mutableStateOf<JwsSigner?>(null) }

    LaunchedEffect(Unit) {
        loading = true

        // Setup HTTP client.
        val httpClient = Oid4vciAsyncHttpClient()
        hoistedHttpClient = httpClient

        // Setup signer.
        val keyManager = KeyManager()
        val keyAlias = "credential/" + UUID.randomUUID().toString()
        keyManager.generateSigningKey(id = keyAlias)
        credentialKeyAlias = keyAlias
        val jwk = keyManager.getJwk(keyAlias)!!.copy()
        val didUrl = generateDidJwkUrl(jwk)
        jwk.setKid(didUrl.toString())

        Log.i("OID4VCI", "JWK = $jwk")

        val signer = object : JwsSigner {
            override suspend fun fetchInfo(): JwsSignerInfo {
                return jwk.fetchInfo()
            }

            override suspend fun signBytes(signingBytes: ByteArray): ByteArray {
                return decodeDerSignature(keyManager.signPayload(keyAlias, signingBytes)!!)
            }
        }
        hoistedSigner = signer

        val clientId = didUrl.did().toString()
        hoistedClientId = clientId

        val oid4vciClient = Oid4vciClient(clientId)
        hoistedOid4vciClient = oid4vciClient

        try {
            val offerUrl = if (url.startsWith("openid-credential-offer://")) {
                url
            } else {
                "openid-credential-offer://$url"
            }

            Log.i("OID4VCI", "Resolving credential offer URL: $offerUrl")
            val credentialOffer = oid4vciClient.resolveOfferUrl(httpClient, offerUrl)
            val credentialIssuer = credentialOffer.credentialIssuer()
            hoistedCredentialIssuer = credentialIssuer
            Log.i("OID4VCI", "Credential Offer resolver, with issuer: $credentialIssuer")

            when (val state = oid4vciClient.acceptOffer(httpClient, credentialOffer)) {
                is CredentialTokenState.Ready -> {
                    Log.i("OID4VCI", "Credential ready to be exchanged")
                    val credentialToken = state.v1
                    val result = exchangeCredential(httpClient, oid4vciClient, clientId, credentialIssuer, signer, credentialToken)
                    if (result != null) {
                        credential = result
                    } else {
                        err = "Deferred credentials not supported"
                    }
                }

                is CredentialTokenState.RequiresTxCode -> {
                    Log.i("OID4VCI", "Transaction code required")
                    pendingTxCodeState = state.v1
                    promptForPin = true
                }

                is CredentialTokenState.RequiresAuthorizationCode -> {
                    Log.i("OID4VCI", "Authorization code grant — launching browser sign-in")
                    val redirectUrl = "sk-showcase-oid4vci-redirect://callback"
                    val waiting = state.v1.proceed(httpClient, redirectUrl)
                    val authUrl = waiting.redirectUrl()
                    Log.i("OID4VCI", "Authorization URL: $authUrl")

                    val customTabs = CustomTabsIntent.Builder().build()
                    customTabs.launchUrl(ctx, Uri.parse(authUrl))

                    val redirectUri = Oid4vciAuthCodeReceiver.flow.first()
                    val errorParam = redirectUri.getQueryParameter("error")
                    val codeParam = redirectUri.getQueryParameter("code")

                    when {
                        errorParam != null -> err = "Authorization error: $errorParam"
                        codeParam.isNullOrEmpty() -> err = "Missing authorization code in callback"
                        else -> {
                            val token = waiting.proceed(httpClient, codeParam)
                            val result = exchangeCredential(httpClient, oid4vciClient, clientId, credentialIssuer, signer, token)
                            if (result != null) {
                                credential = result
                            } else {
                                err = "Deferred credentials not supported"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            err = e.localizedMessage
            e.printStackTrace()
        }
        loading = false
    }

    if (promptForPin && pendingTxCodeState != null) {
        AlertDialog(
            onDismissRequest = {
                promptForPin = false
                pendingTxCodeState = null
                pinInput = ""
                err = "Transaction code cancelled"
            },
            title = { Text("Enter Transaction Code") },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val txCodeState = pendingTxCodeState ?: return@TextButton
                    val pin = pinInput
                    promptForPin = false
                    pendingTxCodeState = null
                    pinInput = ""
                    scope.launch {
                        loading = true
                        try {
                            val httpClient = hoistedHttpClient ?: error("HTTP client unavailable")
                            val oid4vciClient = hoistedOid4vciClient ?: error("OID4VCI client unavailable")
                            val clientId = hoistedClientId ?: error("Client ID unavailable")
                            val credentialIssuer = hoistedCredentialIssuer ?: error("Credential issuer unavailable")
                            val signer = hoistedSigner ?: error("Signer unavailable")

                            val token = txCodeState.proceed(httpClient, pin)
                            val result = exchangeCredential(httpClient, oid4vciClient, clientId, credentialIssuer, signer, token)
                            if (result != null) {
                                credential = result
                            } else {
                                err = "Deferred credentials not supported"
                            }
                        } catch (e: Exception) {
                            err = e.localizedMessage ?: "Transaction code rejected"
                        }
                        loading = false
                    }
                }) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = {
                    promptForPin = false
                    pendingTxCodeState = null
                    pinInput = ""
                    err = "Transaction code cancelled"
                }) { Text("Cancel") }
            },
        )
    }

    if (loading) {
        LoadingView(loadingText = "Loading...")
    } else if (err != null) {
        ErrorView(
            errorTitle = "Error Adding Credential",
            errorDetails = err!!,
            onClose = { navController.navigate(Screen.HomeScreen.route) { popUpTo(0) } }
        )
    } else if (credential != null) {
        AddToWalletView(
            navController = navController,
            rawCredential = credential!!,
            keyAlias = credentialKeyAlias,
            onSuccess = {
                scope.launch {
                    callback?.invoke()
                    navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
                }
            }
        )
    }
}

private suspend fun exchangeCredential(
    httpClient: Oid4vciAsyncHttpClient,
    oid4vciClient: Oid4vciClient,
    clientId: String,
    credentialIssuer: String,
    signer: JwsSigner,
    credentialToken: CredentialToken,
): String? {
    val credentialId = credentialToken.defaultCredentialId()
    Log.i("OID4VCI", "Credential id: $credentialId")

    Log.i("OID4VCI", "Generating PoP...")
    val nonce = credentialToken.getNonce(httpClient)
    Log.i("OID4VCI", "Nonce: $nonce")
    Log.i("OID4VCI", "Signing...")
    val jwt = createJwtProof(clientId, credentialIssuer, null, nonce, signer)
    Log.i("OID4VCI", "PoP JWT = $jwt")
    val proofs = Proofs.Jwt(listOf(jwt))

    Log.i("OID4VCI", "Exchanging Credential...")
    return when (val response = oid4vciClient.exchangeCredential(httpClient, credentialToken, credentialId, proofs)) {
        is CredentialResponse.Deferred -> {
            Log.i("OID4VCI", "Deferred credential received")
            null
        }
        is CredentialResponse.Immediate -> {
            Log.i("OID4VCI", "Credential exchanged!")
            // TODO(batch issuance): only the first credential is taken. Per-credential
            // key + proof for each credential in a batch is a future effort.
            val rawCredential = checkNotNull(response.v1.credentials.first()) { "Missing Credential" }
            rawCredential.payload.toString(Charsets.UTF_8)
        }
    }
}

fun getVCPlaygroundOID4VCIContext(ctx: Context): Map<String, String> {
    val context = mutableMapOf<String, String>()

    context["https://w3id.org/first-responder/v1"] =
        ctx.resources
            .openRawResource(R.raw.w3id_org_first_responder_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/vdl/aamva/v1"] =
        ctx.resources
            .openRawResource(R.raw.w3id_org_vdl_aamva_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/citizenship/v3"] =
        ctx.resources
            .openRawResource(R.raw.w3id_org_citizenship_v3)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.2.json"] =
        ctx.resources
            .openRawResource(R.raw.purl_imsglobal_org_spec_ob_v3p0_context_3_0_2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/citizenship/v4rc1"] =
        ctx.resources
            .openRawResource(R.raw.w3id_org_citizenship_v4rc1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/vc/render-method/v2rc1"] =
        ctx.resources
            .openRawResource(R.raw.w3id_org_vc_render_method_v2rc1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/alumni/v2.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_alumni_v2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/first-responder/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_first_responder_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/shim-render-method-term/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_shim_render_method_term_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/shim-VCv1.1-common-example-terms/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_shim_vcv1_1_common_example_terms_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/utopia-natcert/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_utopia_natcert_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://www.w3.org/ns/controller/v1"] =
        ctx.resources
            .openRawResource(R.raw.w3_org_ns_controller_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/movie-ticket/v2.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_movie_ticket_v2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/food-safety-certification/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_food_safety_certification_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/academic-course-credential/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_academic_course_credential_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/gs1-8110-coupon/v2.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_gs1_8110_coupon_v2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/customer-loyalty/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_customer_loyalty_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://examples.vcplayground.org/contexts/movie-ticket-vcdm-v2/v1.json"] =
        ctx.resources
            .openRawResource(R.raw.examples_vcplayground_org_contexts_movie_ticket_vcdm_v2_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    return context
}
