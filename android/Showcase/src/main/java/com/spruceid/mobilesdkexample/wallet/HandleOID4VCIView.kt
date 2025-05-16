package com.spruceid.mobilesdkexample.wallet

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.AsyncHttpClient
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.HttpRequest
import com.spruceid.mobile.sdk.rs.HttpResponse
import com.spruceid.mobile.sdk.rs.Oid4vci
import com.spruceid.mobile.sdk.rs.Oid4vciExchangeOptions
import com.spruceid.mobile.sdk.rs.generatePopComplete
import com.spruceid.mobile.sdk.rs.generatePopPrepare
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpMethod
import io.ktor.util.toMap
import kotlinx.coroutines.launch

@Composable
fun HandleOID4VCIView(
    navController: NavHostController,
    url: String,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    statusListViewModel: StatusListViewModel
) {
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var credential by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback =
        navController.currentBackStackEntry?.savedStateHandle?.get<suspend () -> Unit>("callback")

    LaunchedEffect(Unit) {
        loading = true
        val client = HttpClient(CIO)
        val oid4vciSession =
            Oid4vci.newWithAsyncClient(
                client =
                    object : AsyncHttpClient {
                        override suspend fun httpClient(
                            request: HttpRequest
                        ): HttpResponse {
                            val res =
                                client.request(request.url) {
                                    method = HttpMethod(request.method)
                                    for ((k, v) in request.headers) {
                                        headers[k] = v
                                    }
                                    setBody(request.body)
                                }

                            return HttpResponse(
                                statusCode = res.status.value.toUShort(),
                                headers =
                                    res.headers.toMap().mapValues {
                                        it.value.joinToString()
                                    },
                                body = res.readBytes()
                            )
                        }
                    }
            )

        val fullUrl = "openid-credential-offer://$url"
        try {
            oid4vciSession.initiateWithOffer(
                credentialOffer = fullUrl,
                clientId = "skit-demo-wallet",
                redirectUrl = "https://spruceid.com"
            )

            val nonce = oid4vciSession.exchangeToken()

            val metadata = oid4vciSession.getMetadata()

            val keyManager = KeyManager()

            if (!keyManager.keyExists(DEFAULT_SIGNING_KEY_ID)) {
                keyManager.generateSigningKey(DEFAULT_SIGNING_KEY_ID)
            }

            val jwk = keyManager.getJwk(id = DEFAULT_SIGNING_KEY_ID)

            val signingInput =
                jwk?.let {
                    generatePopPrepare(
                        audience = metadata.issuer(),
                        nonce = nonce,
                        didMethod = DidMethod.JWK,
                        publicJwk = jwk,
                        durationInSecs = null
                    )
                }

            val signature =
                signingInput?.let {
                    keyManager.signPayload(
                        id = DEFAULT_SIGNING_KEY_ID,
                        payload = signingInput
                    )
                }

            val pop =
                signingInput?.let {
                    signature?.let {
                        generatePopComplete(
                            signingInput = signingInput,
                            signatureDer = signature,
                        )
                    }
                }

            oid4vciSession.setContextMap(getVCPlaygroundOID4VCIContext(ctx = ctx))

            val credentials =
                pop?.let {
                    oid4vciSession.exchangeCredential(
                        proofsOfPossession = listOf(pop),
                        options = Oid4vciExchangeOptions(false),
                    )
                }

            credentials?.forEach { cred ->
                cred.payload.toString(Charsets.UTF_8).let { credential = it }
            }
        } catch (e: Exception) {
            err = e.localizedMessage
            e.printStackTrace()
        }
        loading = false
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
            credentialPacksViewModel = credentialPacksViewModel,
            walletActivityLogsViewModel = walletActivityLogsViewModel,
            statusListViewModel = statusListViewModel,
            onSuccess = {
                scope.launch {
                    callback?.invoke()
                    navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
                }
            }
        )
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
