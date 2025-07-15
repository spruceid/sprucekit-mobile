package com.spruceid.mobile.sdk.dcapi

import StorageManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.registry.provider.selectedEntryId
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.identitycredentials.Credential
import com.google.android.gms.identitycredentials.IntentHelper
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.FieldId180137
import com.spruceid.mobile.sdk.rs.RequestMatch180137
import com.spruceid.mobile.sdk.rs.handleDcApiRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** To be triggered by a GetCredential intent to handle a Digital Credentials "get" request.
 *
 * To alter the user experience you may override [LoadingView] and [ConsentView].
 *
 * Currently only supports the [CredentialPack] format through the [StorageManager].
 *
 * See the below snippet for an example of how to register the intent in your AndroidManifest.xml.
 *
 *         <activity
 *             android:name=".Activity"
 *             android:exported="true"
 *             android:theme="@android:style/Theme.Translucent.NoTitleBar">
 *             <intent-filter>
 *                 <action android:name="androidx.credentials.registry.provider.action.GET_CREDENTIAL" />
 *                 <action android:name="androidx.identitycredentials.action.GET_CREDENTIALS" />
 *                 <category android:name="android.intent.category.DEFAULT" />
 *             </intent-filter>
 *         </activity>
 *
 * @param allowedAuthenticators set the allowed authenticators for approving the transaction. By
 * default, `BIOMETRIC_STRONG` or `DEVICE_CREDENTIAL` are allowed. Consider using only
 * `BIOMETRIC_STRONG` for high-assurance use-cases.
 */
open class Activity(val allowedAuthenticators: Int = BIOMETRIC_STRONG or DEVICE_CREDENTIAL) :
    FragmentActivity() {
    private val storageManager by lazy { StorageManager(application) }
    private var activityState: MutableStateFlow<ActivityState> =
        MutableStateFlow(ActivityState.Processing())

    /**
     * A view that is shown within the bottom sheet during processing.
     *
     * By default, no loading view is shown.
     *
     * To change this behaviour, override [LoadingView].
     */
    @Composable
    open fun LoadingView() {
    }

    /**
     * A view that is shown within the bottom sheet during presentation, allowing a user to consent
     * to individual fields.
     *
     * By default, no consent view is shown, and all requested fields are submitted.
     *
     * To change this behaviour, override [ConsentView].
     */
    @Composable
    open fun ConsentView(
        match: RequestMatch180137,
        origin: String,
        onContinue: (List<FieldId180137>) -> Unit,
        onCancel: () -> Unit
    ) {
        onContinue(match.requestedFields().map { it.id })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        activityState = MutableStateFlow(ActivityState.Processing())

        this.setContent {
            val state = activityState.collectAsState().value
            when (state) {
                is ActivityState.Processing -> BottomSheet { LoadingView() }
                is ActivityState.RequestingConsent -> BottomSheet {
                    ConsentView(
                        state.match,
                        onContinue = {
                            state.consent.value = ConsentOutcome.Approved(it)
                        },
                        onCancel = { state.consent.value = ConsentOutcome.Cancelled() },
                        origin = state.origin,
                    )
                }

                is ActivityState.Authenticating -> {}
            }
        }

        Log.d(TAG, "onCreate")

        val requests: List<OpenID4VPRequest>
        try {
            requests = processIntent();
        } catch (e: Exception) {
            Log.w(TAG, "an error occurred while processing the intent", e)
            val resultData = Intent()
            PendingIntentHandler.setGetCredentialException(
                resultData, GetCredentialUnknownException()
            )
            setResult(RESULT_OK, resultData)
            finish()
            return
        }

        lifecycleScope.launch {
            for (request in requests) {
                try {
                    respond(request)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "an error occurred while responding to a request", e)
                    Log.i(TAG, "attempting to respond to the next request")
                }
            }

            val resultData = Intent()
            Log.w(TAG, "failed to successfully respond to any request")
            PendingIntentHandler.setGetCredentialException(
                resultData, GetCredentialUnknownException()
            )
            setResult(RESULT_OK, resultData)
            finish()
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private fun processIntent(): List<OpenID4VPRequest> {
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            ?: throw Exception("missing request")
        val selectedEntryId = request.selectedEntryId ?: throw Exception("missing selectedEntryId")
        Log.d(TAG, "entryId: $selectedEntryId")

        val dcqlCredId: String
        val credentialPackId: String
        val credentialId: String
        try {
            val selectedEntryIdJson = JSONObject(selectedEntryId)
            dcqlCredId = selectedEntryIdJson.getString("dcql_cred_id")
            val comboId = Registry.idsFromComboId(selectedEntryIdJson.getString("id"))
            credentialPackId = comboId.first
            credentialId = comboId.second
        } catch (e: Exception) {
            throw Exception("unexpected format of entryId", e)
        }

        // TODO: Support requests originating from apps.
        val origin =
            request.callingAppInfo.getOrigin(Registry.defaultTrustedApps)?.substringBefore(":443")
                ?: throw Exception("unknown origin")
        Log.d(TAG, "origin: $origin")

        return request.credentialOptions.mapNotNull { option ->
            if (option is GetDigitalCredentialOption) {
                option
            } else {
                Log.d(TAG, "unsupported option: $option")
                null
            }
        }.map { option -> option.requestJson }
            .flatMap { requestJson -> parseOid4vpRequestJson(requestJson) }
            .map { openid4vp_request ->
                Log.d(TAG, "openid4vp request: ${JSONObject(openid4vp_request)}")
                OpenID4VPRequest(
                    credentialPackId,
                    credentialId,
                    origin,
                    openid4vp_request,
                    dcqlCredId
                )
            }

    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private suspend fun respond(request: OpenID4VPRequest) {
        val pack = CredentialPack.loadPacks(storageManager)
            .firstOrNull { it.id().toString() == request.credentialPackId }
        val credential = pack?.getCredentialById(request.credentialId)
        val mdoc = credential?.asMsoMdoc() ?: throw Exception("selected credential not found")

        val responder =
            handleDcApiRequest(request.dcqlCredId, mdoc, request.origin, request.oid4vpRequestJson)
        val origin = responder.getOrigin()

        val consent: MutableStateFlow<ConsentOutcome?> = MutableStateFlow(null)
        activityState.value = ActivityState.RequestingConsent(responder.getMatch(), consent, origin)

        val consentOutcome = consent.filterNotNull().first()
        val openid4vpResponse: String

        when (consentOutcome) {
            is ConsentOutcome.Cancelled -> throw Exception("user cancelled on the consent screen")
            is ConsentOutcome.Approved -> openid4vpResponse =
                responder.respond(KeyManager(), consentOutcome.approvedFields)
        }

        activityState.value = ActivityState.Authenticating()

        val authenticate: MutableStateFlow<AuthenticationOutcome?> = MutableStateFlow(null)

        val biometricPrompt = BiometricPrompt(
            this@Activity, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "biometric authentication failed")
                    authenticate.value = AuthenticationOutcome.Failure()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.d(TAG, "biometric authentication failed ($errorCode): $errString")
                    authenticate.value = AuthenticationOutcome.Failure()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "biometric authentication succeeded")
                    authenticate.value = AuthenticationOutcome.Success()
                }
            })

        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Verify your identity")
                .setSubtitle("Approve transmission of your data to $origin")
                .setConfirmationRequired(false).setAllowedAuthenticators(allowedAuthenticators)
                .build()
        )

        val authenticationOutcome = authenticate.filterNotNull().first()

        when (authenticationOutcome) {
            is AuthenticationOutcome.Failure -> throw Exception("biometric authentication failed")
            is AuthenticationOutcome.Success -> {}
        }

        var data: JSONObject
        try {
            data = JSONObject(openid4vpResponse)
        } catch (_: Exception) {
            // OpenID4VP response wasn't a JSON object, so is a JWE and can be inserted directly.
            data = JSONObject().put("response", openid4vpResponse)
        }
        val response = JSONObject().put("protocol", "openid4vp").put("data", data).toString()

        Log.d(TAG, "Legacy: $data")
        Log.d(TAG, "Modern: $response")

        val resultData = Intent()

        // This is a temporary solution until Chrome migrate to use
        // the top level DC DigitalCredential json structure.
        // Long term, this should be replaced by a simple
        // `PendingIntentHandler.setGetCredentialResponse(intent, DigitalCredential(response.responseJson))` call.
        IntentHelper.setGetCredentialResponse(
            resultData,
            com.google.android.gms.identitycredentials.GetCredentialResponse(
                Credential(
                    DigitalCredential.TYPE_DIGITAL_CREDENTIAL, Bundle().apply {
                        putByteArray("identityToken", data.toString().toByteArray())
                    })
            )
        )
        PendingIntentHandler.setGetCredentialResponse(
            resultData, GetCredentialResponse(DigitalCredential(response))
        )

        setResult(RESULT_OK, resultData)
        finish()
    }

    private data class OpenID4VPRequest(
        val credentialPackId: String,
        val credentialId: String,
        val origin: String,
        val oid4vpRequestJson: String,
        val dcqlCredId: String
    )

    private sealed class ConsentOutcome {
        class Approved(val approvedFields: List<FieldId180137>) : ConsentOutcome()
        class Cancelled : ConsentOutcome()
    }

    private sealed class AuthenticationOutcome {
        class Success : AuthenticationOutcome()
        class Failure : AuthenticationOutcome()
    }

    private sealed class ActivityState() {
        class Processing() : ActivityState()
        class RequestingConsent(
            val match: RequestMatch180137,
            val consent: MutableStateFlow<ConsentOutcome?>,
            val origin: String
        ) : ActivityState()

        class Authenticating : ActivityState()
    }

    @Composable
    private fun BottomSheet(content: @Composable (ColumnScope.() -> Unit)) {
        Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Card {
                content()
            }
        }
    }

    companion object {
        private fun parseOid4vpRequestJson(requestJson: String): List<String> {
            try {
                val request = JSONObject(requestJson)
                Log.d(TAG, "request: $request");

                // Providers is legacy, can be removed eventually in favour of requests.
                val providers = request.optJSONArray("providers")

                if (providers != null) {
                    return List(providers.length()) { providers[it] as JSONObject }.mapNotNull {
                        if (it.getString("protocol") == "openid4vp") {
                            it.getString("request")
                        } else {
                            null
                        }
                    }
                }

                val requests = request.optJSONArray("requests")

                if (requests != null) {
                    return List(requests.length()) { requests[it] as JSONObject }.mapNotNull {
                        if (it.getString("protocol") == "openid4vp") {
                            it.getString("data")
                        } else {
                            null
                        }
                    }
                }

                Log.d(TAG, "failed to match request to an expected format")
                return emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "an error occurred while parsing the DC-API request", e)
                return emptyList()
            }
        }

        private const val TAG = "dcapi.Activity"
    }
}