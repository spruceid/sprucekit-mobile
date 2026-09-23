package com.spruceid.sprucekit_mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.CredentialPresentData
import com.spruceid.mobile.sdk.IsoMdlPresentation
import com.spruceid.mobile.sdk.PresentationMode
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.nfc.NfcListenManager
import com.spruceid.mobile.sdk.nfc.NfcPresentationError
import com.spruceid.mobile.sdk.rs.ItemsRequest
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adapter implementing the MdlPresentation Pigeon interface for Android
 *
 * Two engagement paths share one [IsoMdlPresentation]:
 *  - QR: `initializeQrPresentation` starts the BLE session at once and the
 *    engagement string comes back as `engagingQrCode`.
 *  - NFC: `initializeNfcPresentation` arms [NfcPresentationService] and
 *    waits. The reader tap delivers a [NegotiatedCarrierInfo], and only then
 *    does the BLE session start, in central client mode.
 */
internal class MdlPresentationAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : MdlPresentation {

    // `immediate` runs a block inline when already on the main thread. The
    // SDK posts NFC callbacks to the main looper, so their state updates land
    // before a queued cancel() instead of after it.
    private val coroutineScope = CoroutineScope(Dispatchers.Main.immediate)

    private var presentation: IsoMdlPresentation? = null
    private var flutterCallback: MdlPresentationCallback? = null
    private var currentState: MdlPresentationStateUpdate = MdlPresentationStateUpdate(state = MdlPresentationState.UNINITIALIZED)
    private var itemsRequests: List<ItemsRequest> = emptyList()
    private var mdoc: Mdoc? = null

    // NFC tap-to-share
    private val nfc = NfcEngagementCoordinator()
    private var activityBinding: ActivityPluginBinding? = null
    private var activityResumed = false
    private var preferredServiceSet = false
    private var nfcStateReceiverRegistered = false

    fun setCallback(callback: MdlPresentationCallback) {
        flutterCallback = callback
    }

    /**
     * Called by the plugin on every ActivityAware transition. The Activity is
     * needed for `CardEmulation.setPreferredService`, which Android only
     * accepts from a resumed Activity.
     */
    fun setActivityBinding(binding: ActivityPluginBinding?) {
        activityBinding?.activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        activityBinding = binding
        val activity = binding?.activity
        if (activity == null) {
            activityResumed = false
            return
        }
        // The lifecycle callbacks do not replay a resume that already
        // happened, so seed from the current state. An add-to-app host can
        // attach the plugin to an Activity that is already resumed.
        activityResumed = (activity as? LifecycleOwner)
            ?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: false
        activity.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        applyPreferredService()
    }

    /** Releases NFC hooks before the Flutter engine goes away. */
    fun dispose() {
        disarmNfc()
        setActivityBinding(null)
        if (NfcPresentationService.listener === nfcListener) {
            NfcPresentationService.listener = null
        }
    }

    @SuppressLint("MissingPermission")
    override fun initializeQrPresentation(
        packId: String,
        credentialId: String,
        callback: (Result<MdlPresentationResult>) -> Unit
    ) {
        val error = createPresentation(packId, credentialId, mode = null)
        if (error != null) {
            callback(Result.success(error))
            return
        }

        updateState(MdlPresentationStateUpdate(state = MdlPresentationState.INITIALIZING))

        coroutineScope.launch {
            startSession(CredentialPresentData.Qr())
        }

        callback(Result.success(MdlPresentationSuccess("Presentation initialized")))
    }

    override fun isNfcPresentationAvailable(): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        val hce = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        return hce && adapter.isEnabled
    }

    override fun initializeNfcPresentation(
        packId: String,
        credentialId: String,
        callback: (Result<MdlPresentationResult>) -> Unit
    ) {
        if (!isNfcPresentationAvailable()) {
            callback(Result.success(MdlPresentationError("NFC is not available on this device")))
            return
        }

        val error = createPresentation(packId, credentialId, mode = PresentationMode.CENTRAL_ONLY)
        if (error != null) {
            callback(Result.success(error))
            return
        }

        check(nfc.arm()) { "coordinator not idle after tearDown" }

        NfcPresentationService.listener = nfcListener
        // First contact with card emulation. Nothing touches the NFC stack
        // before the wallet asks for a tap, so consumers that never use NFC
        // see no change at app start. init is idempotent.
        NfcListenManager.init(context, NfcPresentationService.componentName(context))
        NfcListenManager.userRequested = true
        registerNfcStateReceiver()
        applyPreferredService()

        updateState(MdlPresentationStateUpdate(
            state = MdlPresentationState.INITIALIZING,
            nfcPhase = MdlNfcPhase.WAITING_FOR_TAP
        ))
        callback(Result.success(MdlPresentationSuccess("Waiting for reader tap")))
    }

    override fun getQrCodeUri(): String? {
        return currentState.qrCodeUri
    }

    override fun getCurrentState(): MdlPresentationStateUpdate {
        return currentState
    }

    override fun submitNamespaces(
        selectedNamespaces: Map<String, Map<String, List<String>>>,
        callback: (Result<MdlPresentationResult>) -> Unit
    ) {
        if (presentation == null) {
            callback(Result.success(MdlPresentationError("No active presentation session")))
            return
        }

        // Check if any fields are selected
        val hasSelectedFields = selectedNamespaces.values.any { docTypeNamespaces ->
            docTypeNamespaces.values.any { fields -> fields.isNotEmpty() }
        }

        if (!hasSelectedFields) {
            callback(Result.success(MdlPresentationError("Select at least one attribute to share")))
            return
        }

        try {
            updateState(MdlPresentationStateUpdate(state = MdlPresentationState.SENDING_RESPONSE))
            presentation?.submitNamespaces(selectedNamespaces)
            releaseNfcAfterSession()
            updateState(MdlPresentationStateUpdate(state = MdlPresentationState.SUCCESS))
            callback(Result.success(MdlPresentationSuccess("Response submitted")))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit namespaces", e)
            releaseNfcAfterSession()
            updateState(MdlPresentationStateUpdate(
                state = MdlPresentationState.ERROR,
                error = e.message ?: "Failed to submit namespaces"
            ))
            callback(Result.success(MdlPresentationError("Failed to submit namespaces: ${e.message}")))
        }
    }

    override fun cancel() {
        tearDown()
        updateState(MdlPresentationStateUpdate(state = MdlPresentationState.UNINITIALIZED))
    }

    // MARK: - Session setup

    /**
     * Resolves the credential and builds the presentation. Returns null on
     * success, or the error to hand back to Dart. Any previous session is
     * cancelled first, for both engagement paths.
     */
    @SuppressLint("MissingPermission")
    private fun createPresentation(
        packId: String,
        credentialId: String,
        mode: PresentationMode?
    ): MdlPresentationError? {
        tearDown()

        try {
            val pack = credentialPackAdapter.getNativePack(packId)
                ?: return MdlPresentationError("Credential pack not found: $packId")

            val credential = pack.getCredentialById(credentialId)
                ?: return MdlPresentationError("Credential not found: $credentialId")

            val mdoc = credential.asMsoMdoc()
                ?: return MdlPresentationError("Credential is not an mDoc: $credentialId")
            this.mdoc = mdoc

            val bluetoothManager = getBluetoothManager(context)
                ?: return MdlPresentationError("Bluetooth not available")

            val presentationCallback = object : BLESessionStateDelegate() {
                override fun update(state: Map<String, Any>) {
                    handleStateUpdate(state)
                }

                override fun error(error: Exception) {
                    Log.e(TAG, "Presentation error: ${error.message}", error)
                    releaseNfcAfterSession()
                    updateState(MdlPresentationStateUpdate(
                        state = MdlPresentationState.ERROR,
                        error = error.message ?: "Unknown error"
                    ))
                }
            }

            presentation = IsoMdlPresentation(
                callback = presentationCallback,
                mdoc = mdoc,
                keyAlias = mdoc.keyAlias(),
                bluetoothManager = bluetoothManager,
                context = context,
                mode = mode,
            )
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize presentation", e)
            return MdlPresentationError("Failed to initialize presentation: ${e.message}")
        }
    }

    private fun startSession(data: CredentialPresentData) {
        try {
            presentation?.initialize(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize presentation", e)
            updateState(MdlPresentationStateUpdate(
                state = MdlPresentationState.ERROR,
                error = e.message ?: "Failed to initialize presentation"
            ))
        }
    }

    private fun tearDown() {
        disarmNfc()
        presentation?.terminate()
        presentation = null
        itemsRequests = emptyList()
        mdoc = null
    }

    // MARK: - NFC

    private val nfcListener = object : NfcPresentationService.Listener {
        // Keep answering APDUs after the handover too. The SDK reports the
        // carrier info as soon as the Handover Select is ready, and the
        // reader still reads the NDEF file after that. A service that goes
        // quiet at this point leaves the reader stuck on the tap.
        override fun isListening(): Boolean = nfc.isListening

        override fun onCarrierInfo(carrierInfo: NegotiatedCarrierInfo) {
            if (!nfc.onCarrierInfo()) return
            coroutineScope.launch {
                // A cancel() can land between the callback and this block.
                if (nfc.phase != NfcEngagementCoordinator.Phase.ENGAGED) return@launch
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.INITIALIZING,
                    nfcPhase = MdlNfcPhase.CONNECTING
                ))
                startSession(CredentialPresentData.Nfc(carrierInfo))
            }
        }

        override fun onNegotiationFailed(error: NfcPresentationError) {
            if (!nfc.onNegotiationFailed()) return
            coroutineScope.launch {
                releaseNfcListening()
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.ERROR,
                    error = error.humanReadable
                ))
            }
        }
    }

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_ON)
            val turningOff = state == NfcAdapter.STATE_OFF || state == NfcAdapter.STATE_TURNING_OFF
            if (!turningOff || !nfc.onNfcTurnedOff()) return
            releaseNfcListening()
            updateState(MdlPresentationStateUpdate(
                state = MdlPresentationState.ERROR,
                error = "NFC was turned off",
                nfcPhase = MdlNfcPhase.UNAVAILABLE
            ))
        }
    }

    /**
     * The reader connected over BLE, or the session ended. The NDEF read is
     * over, so the service stops answering taps. Otherwise the phone keeps
     * handing out carrier info for a session nothing scans for.
     */
    private fun releaseNfcAfterSession() {
        if (nfc.onSessionEnded()) releaseNfcListening()
    }

    /** Forgets the attempt and releases the NFC hooks. */
    private fun disarmNfc() {
        nfc.cancel()
        releaseNfcListening()
    }

    /** Stops the service from answering readers. Safe to call more than once. */
    private fun releaseNfcListening() {
        if (NfcPresentationService.listener === nfcListener) {
            NfcPresentationService.listener = null
        }
        if (NfcAdapter.getDefaultAdapter(context) != null) {
            NfcListenManager.userRequested = false
        }
        unregisterNfcStateReceiver()
        clearPreferredService()
    }

    private fun registerNfcStateReceiver() {
        if (nfcStateReceiverRegistered) return
        context.registerReceiver(nfcStateReceiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED))
        nfcStateReceiverRegistered = true
    }

    private fun unregisterNfcStateReceiver() {
        if (!nfcStateReceiverRegistered) return
        try {
            context.unregisterReceiver(nfcStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Already gone with the context. Nothing to release.
        }
        nfcStateReceiverRegistered = false
    }

    /**
     * Makes this service the one Android routes the mdoc AID to while the
     * wallet is on screen, so another wallet with the same AID does not win
     * the tap. Only valid from a resumed Activity, so the lifecycle callbacks
     * call this again on resume. Stays set through the BLE session, since
     * readers poll again after the handover.
     */
    private fun applyPreferredService() {
        if (preferredServiceSet || !activityResumed) return
        if (nfc.phase == NfcEngagementCoordinator.Phase.IDLE) return
        val activity = activityBinding?.activity ?: return
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val component = NfcPresentationService.componentName(activity)
        if (CardEmulation.getInstance(adapter).setPreferredService(activity, component)) {
            preferredServiceSet = true
        } else {
            Log.w(TAG, "setPreferredService failed")
        }
    }

    private fun clearPreferredService() {
        if (!preferredServiceSet) return
        preferredServiceSet = false
        val activity = activityBinding?.activity ?: return
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        if (!CardEmulation.getInstance(adapter).unsetPreferredService(activity)) {
            Log.w(TAG, "unsetPreferredService failed")
        }
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !== activityBinding?.activity) return
            activityResumed = true
            applyPreferredService()
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity !== activityBinding?.activity) return
            // Android drops the preference on pause. Mirror that so resume
            // sets it again.
            clearPreferredService()
            activityResumed = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // MARK: - Internal methods

    private fun updateState(state: MdlPresentationStateUpdate) {
        currentState = state
        // Ensure callback is invoked on the main thread as required by Flutter
        coroutineScope.launch {
            flutterCallback?.onStateChange(state) { }
        }
    }

    private fun handleStateUpdate(state: Map<String, Any>) {
        when {
            state.containsKey("timeout") -> {
                releaseNfcAfterSession()
                updateState(MdlPresentationStateUpdate(state = MdlPresentationState.TIMEOUT))
            }

            state.containsKey("engagingQRCode") -> {
                val qrCodeUri = state["engagingQRCode"] as String
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.ENGAGING_QR_CODE,
                    qrCodeUri = qrCodeUri
                ))
            }

            state.containsKey("selectNamespaces") -> {
                // The reader is on BLE now. NFC has done its job.
                releaseNfcAfterSession()
                @Suppress("UNCHECKED_CAST")
                itemsRequests = state["selectNamespaces"] as List<ItemsRequest>

                // Convert ItemsRequest to MdlItemsRequest
                val mdlItemsRequests = itemsRequests.map { itemsRequest ->
                    val namespaceRequests = itemsRequest.namespaces.map { (namespace, fields) ->
                        val items = fields.map { (fieldName, intentToRetain) ->
                            MdlNamespaceItem(name = fieldName, intentToRetain = intentToRetain)
                        }
                        MdlNamespaceRequest(namespace = namespace, items = items)
                    }
                    MdlItemsRequest(docType = itemsRequest.docType, namespaces = namespaceRequests)
                }

                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.SELECTING_NAMESPACES,
                    itemsRequests = mdlItemsRequests
                ))
            }

            state.containsKey("error") -> {
                releaseNfcAfterSession()
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.ERROR,
                    error = state["error"].toString()
                ))
            }
        }
    }

    private companion object {
        const val TAG = "MdlPresentationAdapter"
    }
}
