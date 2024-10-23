package com.spruceid.mobilesdkexample.wallet

import android.content.Context
import android.util.Base64
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.AsyncHttpClient
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.HttpRequest
import com.spruceid.mobile.sdk.rs.HttpResponse
import com.spruceid.mobile.sdk.rs.Oid4vci
import com.spruceid.mobile.sdk.rs.generatePopComplete
import com.spruceid.mobile.sdk.rs.generatePopPrepare
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.viewmodels.IRawCredentialsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpMethod
import io.ktor.util.toMap
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun OID4VCIView(
    navController: NavHostController,
    rawCredentialsViewModel: IRawCredentialsViewModel
) {
    var loading by remember {
        mutableStateOf(false)
    }
    var err by remember {
        mutableStateOf<String?>(null)
    }
    var credential by remember {
        mutableStateOf<String?>(null)
    }
    val ctx = LocalContext.current

    fun getCredential(credentialOffer: String) {
        loading = true
        val client = HttpClient(CIO)
        val oid4vciSession = Oid4vci.newWithAsyncClient(client = object : AsyncHttpClient {
            override suspend fun httpClient(request: HttpRequest): HttpResponse {
                val res = client.request(request.url) {
                    method = HttpMethod(request.method)
                    for ((k, v) in request.headers) {
                        headers[k] = v
                    }
                    setBody(request.body)
                }

                return HttpResponse(
                    statusCode = res.status.value.toUShort(),
                    headers = res.headers.toMap().mapValues { it.value.joinToString() },
                    body = res.readBytes()
                )
            }

        })

        GlobalScope.async {
            try {
                oid4vciSession.initiateWithOffer(
                    credentialOffer = credentialOffer,
                    clientId = "skit-demo-wallet",
                    redirectUrl = "https://spruceid.com"
                )

                val nonce = oid4vciSession.exchangeToken()

                val metadata = oid4vciSession.getMetadata()

                val keyManager = KeyManager()
                keyManager.generateSigningKey(id = "reference-app/default-signing")
                val jwk = keyManager.getJwk(id = "reference-app/default-signing")

                val signingInput = jwk?.let {
                    generatePopPrepare(
                        audience = metadata.issuer(),
                        nonce = nonce,
                        didMethod = DidMethod.JWK,
                        publicJwk = jwk,
                        durationInSecs = null
                    )
                }

                val signature = signingInput?.let {
                    keyManager.signPayload(
                        id = "reference-app/default-signing",
                        payload = signingInput
                    )
                }

                val pop = signingInput?.let {
                    signature?.let {
                        generatePopComplete(
                            signingInput = signingInput,
                            signature = Base64.encodeToString(
                                signature,
                                Base64.URL_SAFE
                                        or Base64.NO_PADDING
                                        or Base64.NO_WRAP
                            ).toByteArray()
                        )
                    }
                }

                oid4vciSession.setContextMap(getVCPlaygroundOID4VCIContext(ctx = ctx))

                val credentials = pop?.let {
                    oid4vciSession.exchangeCredential(proofsOfPossession = listOf(pop))
                }

                credentials?.forEach { cred ->
                    cred.payload.toString(Charsets.UTF_8).let {
                        // Removes the renderMethod to avoid storage issues
                        // TODO: Remove this when replace the storage component
                        val json = JSONObject(it)
                        json.remove("renderMethod")
                        credential = json.toString()
                    }
                }
            } catch (e: Exception) {
                err = e.localizedMessage
                e.printStackTrace()
            }
            loading = false
        }
    }

    if (loading) {
        LoadingView(loadingText = "Loading...")
    } else if (err != null) {
        ErrorView(
            errorTitle = "Error Adding Credential",
            errorDetails = err!!,
            onClose = {
                navController.navigate(Screen.HomeScreen.route) {
                    popUpTo(0)
                }
            }
        )
    } else if (credential == null) {
        ScanningComponent(
            title = "Scan to Add Credential",
            navController = navController,
            scanningType = ScanningType.QRCODE,
            onRead = ::getCredential
        )
    } else {
        AddToWalletView(
            navController = navController,
            rawCredential = credential!!,
            rawCredentialsViewModel = rawCredentialsViewModel,
        )
    }
}


fun getVCPlaygroundOID4VCIContext(ctx: Context): Map<String, String> {
    val context = mutableMapOf<String, String>()

    context["https://contexts.vcplayground.org/examples/alumni/v1.json"] =
        ctx.resources.openRawResource(R.raw.contexts_vcplayground_org_examples_alumni_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/first-responder/v1"] =
        ctx.resources.openRawResource(R.raw.w3id_org_first_responder_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/vdl/aamva/v1"] =
        ctx.resources.openRawResource(R.raw.w3id_org_vdl_aamva_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://w3id.org/citizenship/v3"] =
        ctx.resources.openRawResource(R.raw.w3id_org_citizenship_v3)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://contexts.vcplayground.org/examples/movie-ticket/v1.json"] =
        ctx.resources.openRawResource(R.raw.contexts_vcplayground_org_examples_movie_ticket_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.2.json"] =
        ctx.resources.openRawResource(R.raw.purl_imsglobal_org_spec_ob_v3p0_context_3_0_2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://contexts.vcplayground.org/examples/food-safety-certification/v1.json"] =
        ctx.resources.openRawResource(R.raw.contexts_vcplayground_org_examples_food_safety_certification_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://contexts.vcplayground.org/examples/gs1-8110-coupon/v2.json"] =
        ctx.resources.openRawResource(R.raw.contexts_vcplayground_org_examples_gs1_8110_coupon_v2)
            .bufferedReader()
            .readLines()
            .joinToString("")

    context["https://contexts.vcplayground.org/examples/customer-loyalty/v1.json"] =
        ctx.resources.openRawResource(R.raw.contexts_vcplayground_org_examples_customer_loyalty_v1)
            .bufferedReader()
            .readLines()
            .joinToString("")

    return context
}


