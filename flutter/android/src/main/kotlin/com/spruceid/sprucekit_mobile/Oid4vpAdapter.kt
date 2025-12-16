package com.spruceid.sprucekit_mobile

import android.content.Context
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.Holder
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PermissionRequest
import com.spruceid.mobile.sdk.rs.PresentableCredential
import com.spruceid.mobile.sdk.rs.PresentationSigner
import com.spruceid.mobile.sdk.rs.ResponseOptions as RsResponseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Signer implementation for OID4VP presentation
 */
class Oid4vpSigner(keyId: String?) : PresentationSigner {
    private val keyId = keyId ?: "default_signing_key"
    private val keyManager = KeyManager()
    private var jwk: String
    private val didJwk = DidMethodUtils(DidMethod.JWK)

    init {
        if (!keyManager.keyExists(this.keyId)) {
            keyManager.generateSigningKey(id = this.keyId)
        }
        this.jwk = keyManager.getJwk(this.keyId)
            ?: throw IllegalArgumentException("Invalid kid")
    }

    override suspend fun sign(payload: ByteArray): ByteArray {
        val signature = keyManager.signPayload(keyId, payload)
            ?: throw IllegalStateException("Failed to sign payload")
        return signature
    }

    override fun algorithm(): String {
        return try {
            val json = JSONObject(jwk)
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

/**
 * OID4VP Pigeon Adapter for Android
 *
 * Handles OpenID for Verifiable Presentation flow
 */
internal class Oid4vpAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : Oid4vp {

    // Session state
    private var holder: Holder? = null
    private var permissionRequest: PermissionRequest? = null
    private var presentableCredentials: List<PresentableCredential> = emptyList()

    override fun createHolder(
        credentialPackIds: List<String>,
        trustedDids: List<String>,
        keyId: String,
        contextMap: Map<String, String>?,
        callback: (Result<Oid4vpResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get native credentials from packs
                val credentials = mutableListOf<ParsedCredential>()
                for (packId in credentialPackIds) {
                    val packCredentials = credentialPackAdapter.getNativeCredentials(packId)
                    credentials.addAll(packCredentials)
                }

                if (credentials.isEmpty()) {
                    callback(Result.success(Oid4vpError(message = "No credentials found in provided packs")))
                    return@launch
                }

                // Create signer
                val signer = Oid4vpSigner(keyId)

                // Create holder
                val newHolder = Holder.newWithCredentials(
                    credentials,
                    trustedDids,
                    signer,
                    contextMap
                )

                synchronized(this@Oid4vpAdapter) {
                    this@Oid4vpAdapter.holder = newHolder
                }

                callback(Result.success(Oid4vpSuccess(message = "Holder created successfully")))
            } catch (e: Exception) {
                callback(Result.success(Oid4vpError(message = e.localizedMessage ?: "Failed to create holder")))
            }
        }
    }

    override fun handleAuthorizationRequest(
        url: String,
        callback: (Result<HandleAuthRequestResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentHolder = synchronized(this@Oid4vpAdapter) { holder }
                if (currentHolder == null) {
                    callback(Result.success(HandleAuthRequestError(
                        message = "Holder not initialized. Call createHolder first."
                    )))
                    return@launch
                }

                // Handle URL format (remove "authorize" if present, similar to Showcase)
                val processedUrl = url.replace("authorize", "")

                // Parse authorization request
                val request = currentHolder.authorizationRequest(processedUrl)
                val credentials = request.credentials()

                synchronized(this@Oid4vpAdapter) {
                    this@Oid4vpAdapter.permissionRequest = request
                    this@Oid4vpAdapter.presentableCredentials = credentials
                }

                if (credentials.isEmpty()) {
                    callback(Result.success(HandleAuthRequestError(
                        message = "No matching credentials found for this verification request"
                    )))
                    return@launch
                }

                // Convert to Pigeon types
                val credentialData = credentials.mapIndexed { index, cred ->
                    PresentableCredentialData(
                        index = index.toLong(),
                        credentialId = cred.asParsedCredential().id(),
                        selectiveDisclosable = cred.selectiveDisclosable()
                    )
                }

                val info = PermissionRequestInfo(
                    clientId = request.clientId(),
                    domain = request.domain(),
                    purpose = request.purpose(),
                    isMultiCredentialSelection = request.isMultiCredentialSelection(),
                    isMultiCredentialMatching = request.isMultiCredentialMatching()
                )

                callback(Result.success(HandleAuthRequestSuccess(
                    credentials = credentialData,
                    info = info
                )))
            } catch (e: Exception) {
                callback(Result.success(HandleAuthRequestError(
                    message = e.localizedMessage ?: "Failed to handle authorization request"
                )))
            }
        }
    }

    override fun getRequestedFields(credentialIndex: Long): List<RequestedFieldData> {
        val request = synchronized(this) { permissionRequest } ?: return emptyList()
        val credentials = synchronized(this) { presentableCredentials }

        if (credentialIndex < 0 || credentialIndex >= credentials.size) {
            return emptyList()
        }

        val credential = credentials[credentialIndex.toInt()]
        val fields = request.requestedFields(credential)
        return fields.map { field ->
            RequestedFieldData(
                id = field.id().toString(),
                name = field.name(),
                path = field.path(),
                required = field.required(),
                retained = field.retained(),
                purpose = field.purpose(),
                inputDescriptorId = field.inputDescriptorId(),
                rawFields = field.rawFields()
            )
        }
    }

    override fun submitResponse(
        selectedCredentialIndices: List<Long>,
        selectedFieldPaths: List<List<String>>,
        options: ResponseOptions,
        callback: (Result<Oid4vpResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentHolder = synchronized(this@Oid4vpAdapter) { holder }
                val request = synchronized(this@Oid4vpAdapter) { permissionRequest }
                val credentials = synchronized(this@Oid4vpAdapter) { presentableCredentials }

                if (currentHolder == null || request == null) {
                    callback(Result.success(Oid4vpError(message = "Session not initialized")))
                    return@launch
                }

                // Map indices to credentials
                val selectedCredentials = selectedCredentialIndices.mapNotNull { index ->
                    if (index >= 0 && index < credentials.size) {
                        credentials[index.toInt()]
                    } else {
                        null
                    }
                }

                if (selectedCredentials.isEmpty()) {
                    callback(Result.success(Oid4vpError(message = "No valid credentials selected")))
                    return@launch
                }

                // Create response options
                val responseOptions = RsResponseOptions(
                    shouldStripQuotes = options.shouldStripQuotes,
                    forceArraySerialization = options.forceArraySerialization,
                    removeVpPathPrefix = options.removeVpPathPrefix
                )

                // Create permission response
                val permissionResponse = request.createPermissionResponse(
                    selectedCredentials,
                    selectedFieldPaths,
                    responseOptions
                )

                // Submit response
                currentHolder.submitPermissionResponse(permissionResponse)

                callback(Result.success(Oid4vpSuccess(message = "Presentation submitted successfully")))
            } catch (e: Exception) {
                callback(Result.success(Oid4vpError(
                    message = e.localizedMessage ?: "Failed to submit response"
                )))
            }
        }
    }

    override fun cancel() {
        synchronized(this) {
            holder = null
            permissionRequest = null
            presentableCredentials = emptyList()
        }
    }
}
