package com.spruceid.sprucekit_mobile

import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.RustLogger
import com.spruceid.mobile.sdk.rs.CryptosuiteEntry
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.OfferedValidity
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PresentationSigner
import com.spruceid.mobile.sdk.rs.StepResult
import com.spruceid.mobile.sdk.rs.StorageManagerInterface
import com.spruceid.mobile.sdk.rs.VcalmException
import com.spruceid.mobile.sdk.rs.VcalmHolder
import com.spruceid.mobile.sdk.rs.VcalmOfferedCredential
import com.spruceid.mobile.sdk.rs.VdcCollection
import com.spruceid.mobile.sdk.rs.Vpr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [StorageManagerInterface]. Keeps the VCALM holder's base
 * [VdcCollection] empty so matching is driven only by `provideCredentials` — the
 * app-wide on-disk store leaked other users' credentials across logout.
 */
internal class InMemoryVdcStorage : StorageManagerInterface {
    private val store = ConcurrentHashMap<String, ByteArray>()
    override suspend fun add(key: String, value: ByteArray) { store[key] = value }
    override suspend fun get(key: String): ByteArray? = store[key]
    override suspend fun list(): List<String> = store.keys.toList()
    override suspend fun remove(key: String) { store.remove(key) }
}

/**
 * Signer for VCALM presentation. 
 *
 * Uses `did:key` (not `did:jwk`) because the target exchange server requires the
 * holder DID to be `did:key`. Conforms to the base [PresentationSigner].
 */
class VcalmSigner(private val fallbackKeyId: String) : PresentationSigner {
    private val keyManager = KeyManager()
    private val didKey = DidMethodUtils(DidMethod.KEY)

    // Only the fallback/legacy key is created on demand; a per-credential key
    // must already exist from issuance, else it can't match the cnf binding.
    private fun ensureKey(keyId: String): String {
        val mayGenerate = keyId.isEmpty() || keyId == fallbackKeyId
        val resolvedId = if (mayGenerate) fallbackKeyId.ifEmpty { DEFAULT_KEY_ID } else keyId
        if (!keyManager.keyExists(resolvedId)) {
            require(mayGenerate) {
                "No signing key for per-credential kid '$resolvedId'; it must exist from issuance"
            }
            keyManager.generateSigningKey(id = resolvedId)
        }
        return resolvedId
    }

    private fun jwkFor(keyId: String): String =
        keyManager.getJwk(ensureKey(keyId))?.toString()
            ?: throw IllegalArgumentException("Invalid kid: $keyId")

    override suspend fun sign(keyId: String, payload: ByteArray): ByteArray =
        keyManager.signPayload(ensureKey(keyId), payload)
            ?: throw IllegalStateException("Failed to sign payload")

    override fun algorithm(): String {
        return "ES256"
    }

    override suspend fun verificationMethod(keyId: String): String {
        return didKey.vmFromJwk(jwkFor(keyId))
    }

    override fun did(keyId: String): String {
        return didKey.didFromJwk(jwkFor(keyId))
    }

    override fun jwk(keyId: String): String {
        return jwkFor(keyId)
    }

    // The VP-wrapper proof stays `ecdsa-rdfc-2019` for challenge/domain binding;
    // any `ecdsa-sd-2023` selective-disclosure proof lives on the credential
    // (derived in Rust), not on the VP wrapper.
    override fun cryptosuite(): String {
        return "ecdsa-rdfc-2019"
    }

    private companion object {
        const val DEFAULT_KEY_ID = "vcalm_demo_key"
    }
}

/**
 * VCALM (`vcapi`) Pigeon adapter for Android.
 *
 * Pure marshaling layer: holds one [VcalmHolder] session, retains matched
 * [ParsedCredential] opaque handles in a key-map, and projects the UniFFI
 * [StepResult] onto the Pigeon [VcalmStepResult]. NO protocol logic lives here
 * — all VCALM logic stays in Rust. Every `@async` method runs on
 * [Dispatchers.IO] and returns a typed result; recoverable failures never throw
 * across the channel.
 */
internal class VcalmAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : Vcalm {

    private companion object {
        const val TAG = "VcalmAdapter"
    }

    private var holder: VcalmHolder? = null

    /**
     * Resolves a Dart-side [VcalmCredentialKey] back to the live opaque
     * [ParsedCredential] handle. `ParsedCredential` is a UniFFI object that
     * cannot cross Pigeon, so the live handles are retained here and Dart only
     * ever holds the lightweight key.
     */
    private var credentialsByKey: Map<VcalmCredentialKey, ParsedCredential> = emptyMap()

    override fun createHolder(
        credentialPackIds: List<String>,
        trustedDids: List<String>,
        keyMap: Map<String, String>,
        fallbackKeyId: String,
        contextMap: Map<String, String>?,
        callback: (Result<VcalmResult>) -> Unit
    ) {
        // Bridge Rust `tracing` into logcat (tag "RustLogger"). Idempotent.
        RustLogger.enable()
        Log.d(TAG, "createHolder: fallbackKeyId=$fallbackKeyId, keyMap=${keyMap.size}, " +
            "trustedDids=${trustedDids.size}, packIds=${credentialPackIds.size}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Isolated (in-memory), NOT the shared on-disk store: matching is
                // seeded only by the wallet packs provided below. See InMemoryVdcStorage.
                val vdc = VdcCollection(InMemoryVdcStorage())
                val signer = VcalmSigner(fallbackKeyId)
                Log.d(TAG, "createHolder: signer fallback did=${signer.did(fallbackKeyId)}")

                val newHolder =
                    VcalmHolder.newSession(
                        vdc,
                        trustedDids,
                        signer,
                        keyMap,
                        fallbackKeyId,
                        contextMap,
                        KeyManager()
                    )

                if (credentialPackIds.isNotEmpty()) {
                    val credentials = mutableListOf<ParsedCredential>()
                    for (packId in credentialPackIds) {
                        try {
                            credentials.addAll(credentialPackAdapter.getNativeCredentials(packId))
                        } catch (e: Exception) {
                            Log.w(TAG, "createHolder: failed to load pack $packId", e)
                        }
                    }
                    Log.d(TAG, "createHolder: seeding ${credentials.size} wallet credential(s) for QBE matching")
                    if (credentials.isNotEmpty()) {
                        newHolder.provideCredentials(credentials)
                    }
                }

                synchronized(this@VcalmAdapter) {
                    this@VcalmAdapter.holder = newHolder
                }

                Log.d(TAG, "createHolder: success")
                callback(Result.success(VcalmSuccess(message = "Holder created successfully")))
            } catch (e: Exception) {
                Log.e(TAG, "createHolder failed", e)
                callback(Result.success(VcalmError(message = e.localizedMessage ?: "Failed to create holder")))
            }
        }
    }

    override fun startExchange(
        url: String,
        authHeader: String?,
        callback: (Result<VcalmStepResult>) -> Unit
    ) {
        Log.d(TAG, "startExchange: url=$url, authHeader=${authHeader != null}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(noHolder()))
                val step = h.startExchange(url, authHeader)
                Log.d(TAG, "startExchange: step=${step::class.simpleName}")
                callback(Result.success(toPigeonStep(step)))
            } catch (e: Exception) {
                Log.e(TAG, "startExchange failed", e)
                callback(Result.success(problem("exchange-error", "Exchange failed", e)))
            }
        }
    }

    override fun matchedCredentials(callback: (Result<List<VcalmCredentialKey>>) -> Unit) {
        Log.d(TAG, "matchedCredentials: called")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                if (h == null) {
                    Log.w(TAG, "matchedCredentials: no holder (call createHolder first)")
                    return@launch callback(Result.success(emptyList()))
                }
                val groups = h.matchedCredentials()
                val keyMap = mutableMapOf<VcalmCredentialKey, ParsedCredential>()
                val keys = mutableListOf<VcalmCredentialKey>()
                for (g in groups) {
                    Log.d(TAG, "matchedCredentials: query ${g.queryIndex} -> ${g.credentials.size} cred(s)")
                    for (m in g.credentials) {
                        // Each match now carries its disclosure mode
                        // (m.selectiveDisclosure); the Pigeon surface keeps the
                        // lightweight key shape, so only the handle is retained here.
                        val c = m.credential
                        val key = VcalmCredentialKey(
                            queryIndex = g.queryIndex.toLong(),
                            credentialId = c.id()
                        )
                        keyMap[key] = c
                        keys.add(key)
                    }
                }
                synchronized(this@VcalmAdapter) {
                    credentialsByKey = keyMap
                }
                Log.d(TAG, "matchedCredentials: total ${keys.size} key(s)")
                callback(Result.success(keys))
            } catch (e: Exception) {
                Log.e(TAG, "matchedCredentials failed", e)
                callback(Result.success(emptyList()))
            }
        }
    }

    override fun requestedFields(callback: (Result<List<VcalmRequestedFieldData>>) -> Unit) {
        Log.d(TAG, "requestedFields: called")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(emptyList()))
                val fields = h.requestedFields()
                Log.d(TAG, "requestedFields: ${fields.size} field(s)")
                callback(Result.success(fields.map { field ->
                    VcalmRequestedFieldData(
                        queryIndex = field.queryIndex.toLong(),
                        path = field.path,
                        value = field.value,
                        required = field.required,
                        purpose = field.purpose
                    )
                }))
            } catch (e: Exception) {
                Log.e(TAG, "requestedFields failed", e)
                callback(Result.success(emptyList()))
            }
        }
    }

    override fun submitPresentation(
        selected: List<VcalmCredentialKey>,
        allowDomainMismatch: Boolean,
        callback: (Result<VcalmStepResult>) -> Unit
    ) {
        Log.d(TAG, "submitPresentation: ${selected.size} selected key(s), allowDomainMismatch=$allowDomainMismatch")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(noHolder()))
                val keyMap = synchronized(this@VcalmAdapter) { credentialsByKey }
                // Suite is server-driven — no suite parameter.
                val creds = selected.mapNotNull { keyMap[it] }
                Log.d(TAG, "submitPresentation: resolved ${creds.size}/${selected.size} handle(s)")
                val step = h.submitPresentation(creds, allowDomainMismatch)
                Log.d(TAG, "submitPresentation: step=${step::class.simpleName}")
                callback(Result.success(toPigeonStep(step)))
            } catch (e: VcalmException.DomainChannelMismatch) {
                // §3.4.3.2 anti-replay refusal — surface a distinct problemType so the
                // host app can ask the user for consent and retry with
                // allowDomainMismatch = true.
                Log.w(TAG, "submitPresentation: domain/channel mismatch (domain=${e.domain}, channel=${e.channel})")
                callback(Result.success(VcalmProblem(
                    problemType = "domain-mismatch",
                    status = null,
                    title = "Verifier domain does not match the exchange channel",
                    detail = "domain=${e.domain}, channel=${e.channel}"
                )))
            } catch (e: Exception) {
                Log.e(TAG, "submitPresentation failed", e)
                callback(Result.success(problem("submit-error", "Presentation failed", e)))
            }
        }
    }

    override fun offeredCredentials(callback: (Result<List<VcalmOfferedCredentialData>>) -> Unit) {
        Log.d(TAG, "offeredCredentials: called")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(emptyList()))
                val offered = h.offeredCredentials()
                Log.d(TAG, "offeredCredentials: ${offered.size} offered")
                callback(Result.success(offered.map(::projectOffered)))
            } catch (e: Exception) {
                Log.e(TAG, "offeredCredentials failed", e)
                callback(Result.success(emptyList()))
            }
        }
    }

    override fun acceptOffer(callback: (Result<VcalmStepResult>) -> Unit) {
        Log.d(TAG, "acceptOffer: called")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(noHolder()))
                val step = h.acceptOffer()
                Log.d(TAG, "acceptOffer: step=${step::class.simpleName}")
                callback(Result.success(toPigeonStep(step)))
            } catch (e: Exception) {
                Log.e(TAG, "acceptOffer failed", e)
                callback(Result.success(problem("accept-error", "Accept failed", e)))
            }
        }
    }

    override fun rejectOffer(callback: (Result<VcalmStepResult>) -> Unit) {
        Log.d(TAG, "rejectOffer: called")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(noHolder()))
                val step = h.rejectOffer()
                Log.d(TAG, "rejectOffer: step=${step::class.simpleName}")
                callback(Result.success(toPigeonStep(step)))
            } catch (e: Exception) {
                Log.e(TAG, "rejectOffer failed", e)
                callback(Result.success(problem("reject-error", "Reject failed", e)))
            }
        }
    }

    override fun cancel() {
        synchronized(this) {
            holder = null
            credentialsByKey = emptyMap()
        }
    }

    // ----- Marshaling helpers -----

    private fun currentHolder(): VcalmHolder? = synchronized(this) { holder }

    /**
     * Projects a UniFFI [StepResult] onto the Pigeon [VcalmStepResult].
     *
     * `suspend` because the Offer arm fetches the offered-credential preview
     * from the holder so the step carries it directly.
     */
    private suspend fun toPigeonStep(step: StepResult): VcalmStepResult = when (step) {
        is StepResult.Request -> VcalmRequest(
            challenge = step.vpr.challenge,
            domain = step.vpr.domain,
            purpose = step.vpr.query.firstNotNullOfOrNull { q ->
                q.credentialQuery.firstNotNullOfOrNull { cq -> cq.reason }
            },
            vprListsSdSuite = vprListsSd(step.vpr)
        )
        is StepResult.Offer -> {
            // `vcs` is an opaque JSON String — do NOT parse structurally; use the
            // holder's read-only preview for display.
            val offered = currentHolder()?.offeredCredentials().orEmpty()
            VcalmOffer(
                credentials = offered.map(::projectOffered),
                hasNextRequest = step.nextVpr != null
            )
        }
        // Surfaced as data only — NEVER auto-followed.
        is StepResult.Redirect -> {
            Log.d(TAG, "toPigeonStep: Redirect (surfaced only)")
            VcalmRedirect(url = step.url)
        }
        is StepResult.Complete -> VcalmComplete(completed = true)
        is StepResult.Problem -> {
            // Server-supplied; logged at debug, not info level.
            Log.d(TAG, "toPigeonStep: Problem type=${step.details.problemType} " +
                "status=${step.details.status} title=${step.details.title}")
            VcalmProblem(
                problemType = step.details.problemType,
                status = step.details.status?.toLong(),
                title = step.details.title,
                detail = step.details.detail
            )
        }
    }

    /**
     * Recomputes the SD-requested hint natively from [Vpr.acceptedCryptosuites],
     * mirroring the Rust `vpr_lists_sd_suite` (which does not cross FFI). This is
     * marshaling for the display indicator, not protocol logic.
     */
    private fun entriesListSd(entries: List<CryptosuiteEntry>?): Boolean =
        entries?.any { entry ->
            when (entry) {
                is CryptosuiteEntry.Name -> entry.v1 == "ecdsa-sd-2023"
                is CryptosuiteEntry.Object -> entry.cryptosuite == "ecdsa-sd-2023"
            }
        } ?: false

    private fun vprListsSd(vpr: Vpr): Boolean {
        // Mirrors Rust vpr_lists_sd_suite: SD may be listed at the VPR top level,
        // at the query level (§3.4.3.1 — the spec's Examples 6/7 placement), OR
        // per-credentialQuery (some deployments use the latter).
        if (entriesListSd(vpr.acceptedCryptosuites)) return true
        return vpr.query.any { q ->
            entriesListSd(q.acceptedCryptosuites) ||
                q.credentialQuery.any { cq -> entriesListSd(cq.acceptedCryptosuites) }
        }
    }

    private fun projectOffered(c: VcalmOfferedCredential): VcalmOfferedCredentialData =
        VcalmOfferedCredentialData(
            issuer = c.issuer,
            types = c.types,
            credentialSubject = c.credentialSubject,
            validity = validityLabel(c.validity),
            rawCredential = c.rawCredential
        )

    private fun validityLabel(v: OfferedValidity): String = when (v) {
        OfferedValidity.VALID -> "valid"
        OfferedValidity.TIME_BOUNDED -> "timeBounded"
        OfferedValidity.PROOF_INVALID -> "proofInvalid"
        OfferedValidity.ENVELOPED -> "enveloped"
        OfferedValidity.UNSUPPORTED_PROOF -> "unsupportedProof"
        OfferedValidity.UNVERIFIABLE -> "unverifiable"
    }

    private fun noHolder(): VcalmProblem = VcalmProblem(
        problemType = "no-holder",
        status = null,
        title = "Holder not initialized",
        detail = "Call createHolder first."
    )

    private fun problem(type: String, title: String, e: Exception): VcalmProblem = VcalmProblem(
        problemType = type,
        status = null,
        title = title,
        detail = e.localizedMessage ?: title
    )
}
