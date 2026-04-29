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
        this.jwk = keyManager.getJwk(this.keyId)?.toString()
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

    /**
     * Resolves a Dart-side `PresentableCredentialKey` back to the live
     * `PresentableCredential` handle. Built from `credentialsGroupedByQuery()`
     * in `handleAuthorizationRequest`: each group entry contributes one
     * `(credentialId, credentialQueryId) -> credential` mapping. The same
     * underlying credential may appear under multiple keys if it satisfies
     * multiple DCQL queries — those are distinct `PresentableCredential`
     * instances on the Rust side, each carrying its own internal query id.
     */
    private var credentialsByKey: Map<PresentableCredentialKey, PresentableCredential> = emptyMap()

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
                    contextMap,
                    KeyManager()
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

                // Build (credentialId, credentialQueryId) -> credential map
                // and the flat credential list for Dart from a single source:
                // the grouped-by-query view. Rust groups by each credential's
                // internal `credential_query_id` (1-to-1 with the flat list),
                // so the union of group entries equals `request.credentials()`.
                val groups = request.credentialsGroupedByQuery()
                val keyMap = mutableMapOf<PresentableCredentialKey, PresentableCredential>()
                val credentialData = mutableListOf<PresentableCredentialData>()
                for (group in groups) {
                    val qid = group.credentialQueryId
                    for (cred in group.credentials) {
                        val cid = cred.asParsedCredential().id()
                        val key = PresentableCredentialKey(
                            credentialId = cid,
                            credentialQueryId = qid
                        )
                        keyMap[key] = cred
                        credentialData.add(
                            PresentableCredentialData(
                                credentialId = cid,
                                credentialQueryId = qid,
                                selectiveDisclosable = cred.selectiveDisclosable()
                            )
                        )
                    }
                }

                synchronized(this@Oid4vpAdapter) {
                    this@Oid4vpAdapter.permissionRequest = request
                    this@Oid4vpAdapter.credentialsByKey = keyMap
                }

                if (credentialData.isEmpty()) {
                    callback(Result.success(HandleAuthRequestError(
                        message = "No matching credentials found for this verification request"
                    )))
                    return@launch
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

    override fun getRequestedFields(key: PresentableCredentialKey): List<RequestedFieldData> {
        val request = synchronized(this) { permissionRequest } ?: return emptyList()
        val credential = synchronized(this) { credentialsByKey[key] } ?: return emptyList()

        val fields = request.requestedFields(credential)
        return fields.map { field ->
            RequestedFieldData(
                id = field.id().toString(),
                name = field.name(),
                path = field.path(),
                required = field.required(),
                retained = field.retained(),
                purpose = field.purpose(),
                credentialQueryId = field.credentialQueryId(),
                rawFields = field.rawFields()
            )
        }
    }

    override fun submitResponse(
        selectedCredentials: List<PresentableCredentialKey>,
        selectedFieldPaths: List<List<String>>,
        options: ResponseOptions,
        callback: (Result<Oid4vpResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentHolder = synchronized(this@Oid4vpAdapter) { holder }
                val request = synchronized(this@Oid4vpAdapter) { permissionRequest }
                val keyMap = synchronized(this@Oid4vpAdapter) { credentialsByKey }

                if (currentHolder == null || request == null) {
                    callback(Result.success(Oid4vpError(message = "Session not initialized")))
                    return@launch
                }

                // Resolve keys to live credential handles
                val resolvedCredentials = selectedCredentials.mapNotNull { keyMap[it] }

                if (resolvedCredentials.isEmpty()) {
                    callback(Result.success(Oid4vpError(message = "No valid credentials selected")))
                    return@launch
                }

                // Create response options
                val responseOptions = RsResponseOptions(
                    forceArraySerialization = options.forceArraySerialization
                )

                // Create permission response
                val permissionResponse = request.createPermissionResponse(
                    resolvedCredentials,
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

    override fun getCredentialRequirements(): List<CredentialRequirementData> {
        val request = synchronized(this) { permissionRequest } ?: return emptyList()
        val keyMap = synchronized(this) { credentialsByKey }

        val requirements = request.credentialRequirements()
        return requirements.map { req ->
            // For each credential in the requirement, pick the first
            // credentialQueryId from `req.credentialQueryIds` (in order)
            // for which `(credentialId, qid)` exists in `keyMap`. Rust's
            // invariant guarantees at least one such qid exists per cred.
            val creds = req.credentials.map { cred ->
                val credId = cred.asParsedCredential().id()
                val qid = req.credentialQueryIds.firstOrNull { qid ->
                    keyMap.containsKey(
                        PresentableCredentialKey(
                            credentialId = credId,
                            credentialQueryId = qid
                        )
                    )
                } ?: error(
                    "No matching credentialQueryId for credentialId=$credId " +
                        "in requirement '${req.displayName}'"
                )
                PresentableCredentialData(
                    credentialId = credId,
                    credentialQueryId = qid,
                    selectiveDisclosable = cred.selectiveDisclosable()
                )
            }
            CredentialRequirementData(
                displayName = req.displayName,
                required = req.required,
                credentialQueryIds = req.credentialQueryIds,
                credentials = creds
            )
        }
    }

    override fun getCredentialsGroupedByQuery(): List<CredentialQueryGroupData> {
        val request = synchronized(this) { permissionRequest } ?: return emptyList()

        val groups = request.credentialsGroupedByQuery()
        return groups.map { group ->
            val qid = group.credentialQueryId
            val creds = group.credentials.map { cred ->
                PresentableCredentialData(
                    credentialId = cred.asParsedCredential().id(),
                    credentialQueryId = qid,
                    selectiveDisclosable = cred.selectiveDisclosable()
                )
            }
            CredentialQueryGroupData(
                credentialQueryId = qid,
                credentials = creds
            )
        }
    }

    override fun getCredentialQueryIds(): List<String> {
        val request = synchronized(this) { permissionRequest } ?: return emptyList()
        return request.credentialQueryIds()
    }

    override fun cancel() {
        synchronized(this) {
            holder = null
            permissionRequest = null
            credentialsByKey = emptyMap()
        }
    }
}
