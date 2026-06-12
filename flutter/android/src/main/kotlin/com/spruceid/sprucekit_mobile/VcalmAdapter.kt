package com.spruceid.sprucekit_mobile

import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.RustLogger
import com.spruceid.mobile.sdk.StorageManager
import com.spruceid.mobile.sdk.rs.CryptosuiteEntry
import com.spruceid.mobile.sdk.rs.DidMethod
import com.spruceid.mobile.sdk.rs.DidMethodUtils
import com.spruceid.mobile.sdk.rs.OfferedValidity
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PresentationSigner
import com.spruceid.mobile.sdk.rs.StepResult
import com.spruceid.mobile.sdk.rs.VcalmHolder
import com.spruceid.mobile.sdk.rs.VcalmOfferedCredential
import com.spruceid.mobile.sdk.rs.VdcCollection
import com.spruceid.mobile.sdk.rs.Vpr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Signer for VCALM presentation.
 *
 * Uses `did:key` (not `did:jwk`) because the target exchange server requires the
 * holder DID to be `did:key`. Conforms to the base [PresentationSigner].
 */
class VcalmSigner(keyId: String?) : PresentationSigner {
    private val keyId = keyId ?: "vcalm_demo_key"
    private val keyManager = KeyManager()
    private var jwk: String

    private val didKey = DidMethodUtils(DidMethod.KEY)

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
            JSONObject(jwk).getString("alg")
        } catch (_: Exception) {
            "ES256"
        }
    }

    override suspend fun verificationMethod(): String {
        return didKey.vmFromJwk(jwk)
    }

    override fun did(): String {
        return didKey.didFromJwk(jwk)
    }

    override fun jwk(): String {
        return jwk
    }

    // The VP-wrapper proof stays `ecdsa-rdfc-2019` for challenge/domain binding;
    // any `ecdsa-sd-2023` selective-disclosure proof lives on the credential
    // (derived in Rust), not on the VP wrapper.
    override fun cryptosuite(): String {
        return "ecdsa-rdfc-2019"
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
        keyId: String,
        contextMap: Map<String, String>?,
        callback: (Result<VcalmResult>) -> Unit
    ) {
        // Bridge Rust `tracing` into logcat (tag "RustLogger"). Idempotent.
        RustLogger.enable()
        Log.d(TAG, "createHolder: keyId=$keyId, trustedDids=${trustedDids.size}, " +
            "packIds=${credentialPackIds.size}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The holder's own VdcCollection receives issuance (acceptOffer)
                // credentials. To ALSO make the host app's existing wallet
                // credentials presentable via QBE matching, load the passed packs
                // into native ParsedCredential handles and seed the holder via
                // provideCredentials.
                val vdc = VdcCollection(StorageManager(context))
                val signer = VcalmSigner(keyId)
                Log.d(TAG, "createHolder: signer did=${signer.did()}")

                val newHolder =
                    VcalmHolder.newSession(vdc, trustedDids, signer, contextMap, KeyManager())

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
        callback: (Result<VcalmStepResult>) -> Unit
    ) {
        Log.d(TAG, "submitPresentation: ${selected.size} selected key(s)")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = currentHolder()
                    ?: return@launch callback(Result.success(noHolder()))
                val keyMap = synchronized(this@VcalmAdapter) { credentialsByKey }
                // Suite is server-driven — no suite parameter.
                val creds = selected.mapNotNull { keyMap[it] }
                Log.d(TAG, "submitPresentation: resolved ${creds.size}/${selected.size} handle(s)")
                // allowDomainMismatch=false: the §3.4.3.2 domain/channel anti-replay
                // check refuses by default; a mismatch surfaces as a problem result.
                val step = h.submitPresentation(creds, false)
                Log.d(TAG, "submitPresentation: step=${step::class.simpleName}")
                callback(Result.success(toPigeonStep(step)))
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
