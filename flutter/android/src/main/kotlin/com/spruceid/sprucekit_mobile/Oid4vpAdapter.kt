package com.spruceid.sprucekit_mobile

import android.content.Context
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.Oid4vpHolder
import com.spruceid.mobile.sdk.rs.Oid4vpPresentableCredential
import com.spruceid.mobile.sdk.rs.Oid4vpPresentationSigner
import com.spruceid.mobile.sdk.rs.Oid4vpSession
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.Oid4vpCompatibilityMode as RsOid4vpCompatibilityMode
import com.spruceid.mobile.sdk.rs.Oid4vpResponseOptions as RsResponseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Signer implementation for OID4VP presentation
 */
class Oid4vpSigner(keyId: String?) : Oid4vpPresentationSigner {
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
 * Handles OpenID for Verifiable Presentation flow.
 *
 * Backed by the version-agnostic OID4VP facade (`Oid4vpHolder` /
 * `Oid4vpSession`), which negotiates OID4VP 1.0 or Draft 18 per request.
 * The negotiated version is chosen by the `mode` passed to
 * `handleAuthorizationRequest`.
 */
internal class Oid4vpAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : Oid4vp {

    // Session state
    private var holder: Oid4vpHolder? = null
    private var session: Oid4vpSession? = null

    /**
     * Resolves a Dart-side `PresentableCredentialKey` back to the live
     * `Oid4vpPresentableCredential` handle. Built from `session.credentials()`
     * in `handleAuthorizationRequest`, grouped by each credential's `matchId`
     * (the DCQL `credential_query_id` for v1, the input-descriptor id for
     * Draft 18). The same underlying credential may appear under multiple keys
     * when it satisfies multiple queries — those are distinct
     * `Oid4vpPresentableCredential` instances on the Rust side.
     */
    private var credentialsByKey: Map<PresentableCredentialKey, Oid4vpPresentableCredential> = emptyMap()

    /** Maps the pigeon-facing compatibility mode to the Rust facade enum. */
    private fun rustMode(mode: Oid4vpCompatibilityMode): RsOid4vpCompatibilityMode =
        when (mode) {
            Oid4vpCompatibilityMode.AUTO -> RsOid4vpCompatibilityMode.AUTO
            Oid4vpCompatibilityMode.V1 -> RsOid4vpCompatibilityMode.V1
            Oid4vpCompatibilityMode.DRAFT18 -> RsOid4vpCompatibilityMode.DRAFT18
            Oid4vpCompatibilityMode.DRAFT13 -> RsOid4vpCompatibilityMode.DRAFT13
        }

    /**
     * Groups the session's presentable credentials by `matchId`, preserving
     * first-appearance order. Single source of truth for the key map, the flat
     * credential list and the grouped-by-query view.
     */
    private fun groupedByQuery(
        session: Oid4vpSession
    ): List<Pair<String, List<Oid4vpPresentableCredential>>> {
        val map = LinkedHashMap<String, MutableList<Oid4vpPresentableCredential>>()
        for (cred in session.credentials()) {
            map.getOrPut(cred.matchId()) { mutableListOf() }.add(cred)
        }
        return map.map { (qid, creds) -> qid to creds }
    }

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

                // Create holder (version-agnostic facade)
                val newHolder = Oid4vpHolder.newWithCredentials(
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
        mode: Oid4vpCompatibilityMode,
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

                // Start a session, negotiating the OID4VP version per `mode`.
                val session = currentHolder.startWithCompatibilityMode(processedUrl, rustMode(mode))

                // Build (credentialId, matchId) -> credential map and the flat
                // credential list for Dart from a single source: credentials
                // grouped by their `matchId`.
                val groups = groupedByQuery(session)
                val keyMap = mutableMapOf<PresentableCredentialKey, Oid4vpPresentableCredential>()
                val credentialData = mutableListOf<PresentableCredentialData>()
                for ((qid, creds) in groups) {
                    for (cred in creds) {
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
                    this@Oid4vpAdapter.session = session
                    this@Oid4vpAdapter.credentialsByKey = keyMap
                }

                if (credentialData.isEmpty()) {
                    callback(Result.success(HandleAuthRequestError(
                        message = "No matching credentials found for this verification request"
                    )))
                    return@launch
                }

                val info = PermissionRequestInfo(
                    clientId = session.clientId(),
                    domain = session.domain(),
                    purpose = session.purpose(),
                    isMultiCredentialSelection = session.isMultiCredentialSelection(),
                    isMultiCredentialMatching = session.isMultiCredentialMatching()
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
        val session = synchronized(this) { session } ?: return emptyList()
        val credential = synchronized(this) { credentialsByKey[key] } ?: return emptyList()

        val fields = session.requestedFields(credential)
        return fields.map { field ->
            RequestedFieldData(
                id = field.id,
                name = field.name,
                path = field.path,
                required = field.required,
                retained = field.retained,
                purpose = field.purpose,
                credentialQueryId = field.matchId,
                rawFields = field.rawFields
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
                val currentSession = synchronized(this@Oid4vpAdapter) { session }
                val keyMap = synchronized(this@Oid4vpAdapter) { credentialsByKey }

                if (currentSession == null) {
                    callback(Result.success(Oid4vpError(message = "Session not initialized")))
                    return@launch
                }

                // Resolve keys to live credential handles
                val resolvedCredentials = selectedCredentials.mapNotNull { keyMap[it] }

                if (resolvedCredentials.isEmpty()) {
                    callback(Result.success(Oid4vpError(message = "No valid credentials selected")))
                    return@launch
                }

                // Create response options. `shouldStripQuotes` and
                // `removeVpPathPrefix` are Draft 18-only knobs not surfaced by
                // the pigeon API; default them off.
                val responseOptions = RsResponseOptions(
                    forceArraySerialization = options.forceArraySerialization,
                    shouldStripQuotes = false,
                    removeVpPathPrefix = false
                )

                // Create and submit the permission response on the session.
                val permissionResponse = currentSession.createPermissionResponse(
                    resolvedCredentials,
                    selectedFieldPaths,
                    responseOptions
                )

                currentSession.submitPermissionResponse(permissionResponse)

                callback(Result.success(Oid4vpSuccess(message = "Presentation submitted successfully")))
            } catch (e: Exception) {
                callback(Result.success(Oid4vpError(
                    message = e.localizedMessage ?: "Failed to submit response"
                )))
            }
        }
    }

    override fun getCredentialRequirements(): List<CredentialRequirementData> {
        val session = synchronized(this) { session } ?: return emptyList()

        val requirements = session.requirements()
        return requirements.map { req ->
            // The facade encodes the requirement's credential query ids as a
            // "|"-joined string in `id`; split it back into the list. Each
            // credential carries its own `matchId`, consistent with the key map
            // built in `handleAuthorizationRequest`.
            val queryIds = req.id.split("|")
            val creds = req.credentials.map { cred ->
                PresentableCredentialData(
                    credentialId = cred.asParsedCredential().id(),
                    credentialQueryId = cred.matchId(),
                    selectiveDisclosable = cred.selectiveDisclosable()
                )
            }
            CredentialRequirementData(
                displayName = req.displayName,
                required = req.required,
                credentialQueryIds = queryIds,
                credentials = creds
            )
        }
    }

    override fun getCredentialsGroupedByQuery(): List<CredentialQueryGroupData> {
        val session = synchronized(this) { session } ?: return emptyList()

        return groupedByQuery(session).map { (qid, creds) ->
            val credentialData = creds.map { cred ->
                PresentableCredentialData(
                    credentialId = cred.asParsedCredential().id(),
                    credentialQueryId = qid,
                    selectiveDisclosable = cred.selectiveDisclosable()
                )
            }
            CredentialQueryGroupData(
                credentialQueryId = qid,
                credentials = credentialData
            )
        }
    }

    override fun getCredentialQueryIds(): List<String> {
        val session = synchronized(this) { session } ?: return emptyList()
        return groupedByQuery(session).map { it.first }
    }

    override fun cancel() {
        synchronized(this) {
            holder = null
            session = null
            credentialsByKey = emptyMap()
        }
    }
}
