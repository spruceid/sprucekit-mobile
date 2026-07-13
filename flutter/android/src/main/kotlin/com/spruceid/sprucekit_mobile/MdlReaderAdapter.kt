package com.spruceid.sprucekit_mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.IsoMdlReader
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.nfc.NfcReaderEngagement
import com.spruceid.mobile.sdk.rs.AuthenticationStatus
import com.spruceid.mobile.sdk.rs.MdlReaderResponseData
import com.spruceid.mobile.sdk.rs.ReaderHandover
import com.spruceid.mobile.sdk.rs.verifiedResponseAsJsonString
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Adapter implementing the MdlReader Pigeon interface for Android.
 *
 * Wraps two engagement paths into a unified state machine:
 *   - NFC: uses [NfcReaderEngagement] (low-level, non-Compose) to drive
 *     reader mode and APDU handover. On [NfcReaderEngagement.Event.Success]
 *     we feed the produced [ReaderHandover] into [IsoMdlReader].
 *   - QR: skip the NFC step entirely; build a [ReaderHandover.newQr] from
 *     the scanned URI and instantiate [IsoMdlReader] directly.
 *
 * Both paths converge on [IsoMdlReader] + [BLESessionStateDelegate] which
 * already parses the response (via Rust `handleResponse`) and surfaces
 * a typed [MdlReaderResponseData] in `state["mdl"]`. We don't need to
 * call `handleMdlReaderResponseData` ourselves.
 *
 * Lifecycle: NFC reader-mode requires an [Activity] for
 * `NfcAdapter.enableReaderMode`, so this adapter is plumbed an
 * [ActivityPluginBinding] by [SprucekitMobilePlugin] (which implements
 * [io.flutter.embedding.engine.plugins.activity.ActivityAware]). QR-only
 * sessions don't need the activity.
 */
internal class MdlReaderAdapter(
    private val context: Context,
) : MdlReader {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val mainScope = coroutineScope

    private var nfcEngagement: NfcReaderEngagement? = null
    private var reader: IsoMdlReader? = null
    private var flutterCallback: MdlReaderCallback? = null
    private var currentState: MdlReaderStateUpdate =
        MdlReaderStateUpdate(state = MdlReaderState.UNINITIALIZED)
    private var activityBinding: ActivityPluginBinding? = null

    fun setCallback(callback: MdlReaderCallback) {
        flutterCallback = callback
    }

    fun setActivityBinding(binding: ActivityPluginBinding?) {
        activityBinding = binding
    }

    override fun isNfcSupported(): Boolean {
        val activity = activityBinding?.activity ?: return false
        val nfcManager =
            activity.getSystemService(Context.NFC_SERVICE) as? android.nfc.NfcManager
        return nfcManager?.defaultAdapter != null
    }

    @SuppressLint("MissingPermission")
    override fun startNfcReader(
        query: Map<String, Map<String, Boolean>>,
        trustedRoots: List<String>,
    ) {
        // Tear down any previous session first.
        cleanupInternal()

        val binding = activityBinding
        if (binding == null) {
            updateState(
                MdlReaderStateUpdate(
                    state = MdlReaderState.ERROR,
                    error = "Plugin not attached to an Activity",
                ),
            )
            return
        }
        val activity: Activity = binding.activity
        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner == null) {
            updateState(
                MdlReaderStateUpdate(
                    state = MdlReaderState.ERROR,
                    error = "Host Activity must implement LifecycleOwner " +
                        "(use FlutterFragmentActivity)",
                ),
            )
            return
        }

        // Pre-flight check for NFC availability — surface a clear terminal
        // state instead of `error` if NFC is absent / off, so the host UI
        // can render an "enable NFC" prompt.
        val nfcManager =
            activity.getSystemService(Context.NFC_SERVICE) as? android.nfc.NfcManager
        val adapter = nfcManager?.defaultAdapter
        if (adapter == null) {
            updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_UNSUPPORTED))
            return
        }
        if (!adapter.isEnabled) {
            updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_DISABLED))
            return
        }

        val engagement = NfcReaderEngagement(activity) { event ->
            when (event) {
                NfcReaderEngagement.Event.NfcUnsupported ->
                    updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_UNSUPPORTED))

                NfcReaderEngagement.Event.NfcDisabled ->
                    updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_DISABLED))

                NfcReaderEngagement.Event.WaitingForTag ->
                    updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_WAITING_FOR_TAG))

                NfcReaderEngagement.Event.Exchanging ->
                    updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_EXCHANGING))

                is NfcReaderEngagement.Event.TransientError -> {
                    // Recoverable; SDK auto-emits WaitingForTag after.
                    // Don't surface a state change to the host.
                    Log.i(TAG, "Transient NFC error", event.cause)
                }

                is NfcReaderEngagement.Event.ProtocolError ->
                    updateState(
                        MdlReaderStateUpdate(
                            state = MdlReaderState.ERROR,
                            error = event.cause.message ?: "NFC handover protocol error",
                        ),
                    )

                is NfcReaderEngagement.Event.Success ->
                    onHandover(event.handover, query, trustedRoots)
            }
        }
        nfcEngagement = engagement
        engagement.bindToLifecycle(lifecycleOwner)
        engagement.setActive(true)
        // Event.start() (idempotent) is invoked by bindToLifecycle → ON_RESUME;
        // emit waiting eagerly so UI doesn't flash empty state on cold start.
        updateState(MdlReaderStateUpdate(state = MdlReaderState.NFC_WAITING_FOR_TAG))
    }

    @SuppressLint("MissingPermission")
    override fun startQrReader(
        qrUri: String,
        query: Map<String, Map<String, Boolean>>,
        trustedRoots: List<String>,
    ) {
        cleanupInternal()
        try {
            val handover = ReaderHandover.newQr(qrUri)
            onHandover(handover, query, trustedRoots)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build handover from QR URI", e)
            updateState(
                MdlReaderStateUpdate(
                    state = MdlReaderState.ERROR,
                    error = "Invalid QR engagement: ${e.message}",
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun onHandover(
        handover: ReaderHandover,
        query: Map<String, Map<String, Boolean>>,
        trustedRoots: List<String>,
    ) {
        // NFC engagement already auto-deactivated after Success; we keep
        // nfcEngagement around so the lifecycle observer can release it.
        updateState(MdlReaderStateUpdate(state = MdlReaderState.BLE_CONNECTING))

        val bluetoothManager = getBluetoothManager(context)
        if (bluetoothManager == null) {
            updateState(
                MdlReaderStateUpdate(
                    state = MdlReaderState.ERROR,
                    error = "Bluetooth not available",
                ),
            )
            return
        }

        val bleCallback = object : BLESessionStateDelegate() {
            override fun update(state: Map<String, Any>) {
                // Hop to main thread; BLE callbacks fire on Android's
                // Bluetooth executor, not main, and Pigeon callbacks must
                // run on main.
                mainScope.launch { handleBleStateUpdate(state) }
            }

            override fun error(error: Exception) {
                Log.e(TAG, "BLE error", error)
                mainScope.launch {
                    updateState(
                        MdlReaderStateUpdate(
                            state = MdlReaderState.ERROR,
                            error = error.message ?: "BLE error",
                        ),
                    )
                }
            }
        }

        try {
            reader = IsoMdlReader(
                bleCallback,
                handover,
                query,
                trustedRoots,
                bluetoothManager,
                context.applicationContext,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to construct IsoMdlReader", e)
            updateState(
                MdlReaderStateUpdate(
                    state = MdlReaderState.ERROR,
                    error = e.message ?: "Failed to start BLE session",
                ),
            )
        }
    }

    /**
     * Translate the loose `state: Map<String, Any>` contract surfaced by
     * `BLESessionStateDelegate.update` into Pigeon state updates.
     *
     * The "mdl" key is the success signal. Both BLE transport paths
     * (`TransportBlePeripheralServerReader` for reader-as-peripheral and
     * `TransportBleCentralClient` for reader-as-central) put the **raw
     * encrypted device-response bytes** under "mdl" — NOT a parsed
     * [MdlReaderResponseData]. (The `IsoMdlReader.handleResponse` /
     * `handleMdlReaderResponseData` are exposed for the caller to do the
     * parsing themselves.)
     *
     * We call [IsoMdlReader.handleMdlReaderResponseData] here to run the
     * full Rust `handleResponse` (decrypt + parse + verify issuer signature
     * + verify cert chain to trust anchor + verify device auth).
     */
    private fun handleBleStateUpdate(state: Map<String, Any>) {
        when {
            state.containsKey("mdl") -> {
                val raw = state["mdl"] as? ByteArray
                if (raw == null) {
                    updateState(
                        MdlReaderStateUpdate(
                            state = MdlReaderState.ERROR,
                            error = "Unexpected `mdl` payload type: " +
                                "${state["mdl"]?.javaClass?.name}",
                        ),
                    )
                    return
                }
                val current = reader
                if (current == null) {
                    updateState(
                        MdlReaderStateUpdate(
                            state = MdlReaderState.ERROR,
                            error = "Reader was torn down before response arrived",
                        ),
                    )
                    return
                }
                try {
                    val parsed = current.handleMdlReaderResponseData(raw)
                    updateState(
                        MdlReaderStateUpdate(
                            state = MdlReaderState.SUCCESS,
                            response = parsed.toPigeon(),
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "handleResponse failed", e)
                    updateState(
                        MdlReaderStateUpdate(
                            state = MdlReaderState.ERROR,
                            error = e.message ?: "Failed to parse device response",
                        ),
                    )
                }
            }

            state.containsKey("error") -> {
                updateState(
                    MdlReaderStateUpdate(
                        state = MdlReaderState.ERROR,
                        error = state["error"].toString(),
                    ),
                )
            }

            // BLE has finer-grained progress signals (engagingQRCode,
            // selectNamespaces, etc.) used by the holder flow; reader-side
            // we don't need them. The bleConnecting / bleReceivingResponse
            // transitions are emitted explicitly at onHandover() and on
            // first non-mdl callback respectively.
            else -> {
                if (currentState.state == MdlReaderState.BLE_CONNECTING) {
                    updateState(
                        MdlReaderStateUpdate(state = MdlReaderState.BLE_RECEIVING_RESPONSE),
                    )
                }
            }
        }
    }

    override fun cancel() {
        cleanupInternal()
        updateState(MdlReaderStateUpdate(state = MdlReaderState.UNINITIALIZED))
    }

    /**
     * Fully release native + coroutine resources held by this adapter.
     * Called by [SprucekitMobilePlugin.onDetachedFromEngine] when the
     * Flutter engine is going away. After [dispose] this adapter is
     * unusable; any pending state updates queued on [mainScope] are
     * cancelled so they can't try to invoke the Flutter callback after
     * the binary messenger is gone.
     */
    fun dispose() {
        cleanupInternal()
        mainScope.cancel()
        flutterCallback = null
        activityBinding = null
    }

    /**
     * Tear down SDK handles only. Does NOT emit a state update — callers
     * decide what state to land in after cleanup.
     */
    private fun cleanupInternal() {
        nfcEngagement?.release()
        nfcEngagement = null
        reader = null
    }

    private fun updateState(state: MdlReaderStateUpdate) {
        currentState = state
        // Pigeon FlutterApi callbacks must be invoked on the main thread.
        // Wrap in try/catch as defense-in-depth: if the Flutter engine is
        // tearing down while a BLE/NFC callback is still in flight, the
        // BinaryMessenger may be unavailable and the call would throw.
        // We swallow + log because there is no Flutter side left to notify.
        mainScope.launch {
            try {
                flutterCallback?.onStateChange(state) { }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to deliver state update; engine likely gone", e)
            }
        }
    }

    /**
     * Serialize [MdlReaderResponseData] into the Pigeon wire shape.
     *
     * `verifiedResponse` is JSON-encoded via the Rust-side helper rather
     * than transported as a typed nested map. See [MdlReadResponse] doc
     * for the rationale (Pigeon recursive-type OOM + nested-Map cast bug).
     */
    private fun MdlReaderResponseData.toPigeon(): MdlReadResponse {
        // `verifiedResponseAsJsonString` consumes the response by value in
        // Rust, but UniFFI generates a Kotlin function that takes the
        // struct by reference. Safe to call here.
        val verifiedJson = verifiedResponseAsJsonString(this)
        return MdlReadResponse(
            verifiedResponseJson = verifiedJson,
            docTypes = docTypes,
            issuerAuthentication = issuerAuthentication.toPigeon(),
            deviceAuthentication = deviceAuthentication.toPigeon(),
            errors = errors,
        )
    }

    private fun AuthenticationStatus.toPigeon(): MdlAuthenticationStatus =
        when (this) {
            AuthenticationStatus.VALID -> MdlAuthenticationStatus.VALID
            AuthenticationStatus.INVALID -> MdlAuthenticationStatus.INVALID
            AuthenticationStatus.UNCHECKED -> MdlAuthenticationStatus.UNCHECKED
        }

    companion object {
        private const val TAG = "MdlReaderAdapter"
    }
}
