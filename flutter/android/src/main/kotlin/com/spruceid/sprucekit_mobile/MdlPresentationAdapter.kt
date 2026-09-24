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

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

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

    // Incremented by every disarm. A block launched by an NFC callback
    // compares the value it captured, so a block queued before a cancel()
    // or a new session does not act on the session that replaced it.
    private var nfcGeneration = 0

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

    /** Ends the session and releases the NFC hooks before the Flutter engine goes away. */
    fun dispose() {
        tearDown()
        setActivityBinding(null)
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

    override fun isNfcPresentationAvailable(): Boolean = nfcUnavailableReason() == null

    /**
     * Null when the phone can answer a reader tap. Otherwise the reason, as
     * the error message for Dart. Each case has its own text so the wallet
     * can tell "turn NFC on" from "this phone cannot".
     */
    private fun nfcUnavailableReason(): String? {
        val adapter = NfcAdapter.getDefaultAdapter(context)
            ?: return "NFC is not supported on this device"
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return "NFC host card emulation is not supported on this device"
        }
        if (!isNfcServiceDeclared()) {
            return "The NFC presentation service is not declared in the app manifest"
        }
        if (!adapter.isEnabled) return "NFC is turned off"
        return null
    }

    /**
     * False when the app did not declare the plugin's service in its
     * manifest. `CardEmulation` returns false for an unknown component and
     * throws nothing, so without this check the tap screen waits forever.
     */
    private fun isNfcServiceDeclared(): Boolean {
        return try {
            context.packageManager.getServiceInfo(NfcPresentationService.componentName(context), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun initializeNfcPresentation(
        packId: String,
        credentialId: String,
        callback: (Result<MdlPresentationResult>) -> Unit
    ) {
        val unavailable = nfcUnavailableReason()
        if (unavailable != null) {
            callback(Result.success(MdlPresentationError(unavailable)))
            return
        }

        val error = createPresentation(packId, credentialId, mode = PresentationMode.CENTRAL_ONLY)
        if (error != null) {
            callback(Result.success(error))
            return
        }

        // Pigeon does not catch exceptions from an async handler, so a
        // failure here must become an error result, not a crash.
        try {
            nfc.arm()
            NfcPresentationService.listener = nfcListener
            // Registers the NDEF AID beside the manifest's mdoc AID for the
            // duration of the tap. init is idempotent.
            NfcListenManager.init(context, NfcPresentationService.componentName(context))
            NfcListenManager.userRequested = true
            registerNfcStateReceiver()
            applyPreferredService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to arm NFC presentation", e)
            tearDown()
            callback(Result.success(MdlPresentationError("Failed to arm NFC presentation: ${e.message}")))
            return
        }

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
            updateState(MdlPresentationStateUpdate(state = MdlPresentationState.SUCCESS))
            callback(Result.success(MdlPresentationSuccess("Response submitted")))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit namespaces", e)
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
            releaseNfcAfterSession()
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
        override fun isListening(): Boolean = nfc.isListening

        override fun onCarrierInfo(carrierInfo: NegotiatedCarrierInfo) {
            if (!nfc.onCarrierInfo()) return
            val generation = nfcGeneration
            coroutineScope.launch {
                // A cancel() or a new session can land between the callback
                // and this block.
                if (generation != nfcGeneration) return@launch
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.INITIALIZING,
                    nfcPhase = MdlNfcPhase.CONNECTING
                ))
                startSession(CredentialPresentData.Nfc(carrierInfo))
            }
        }

        override fun onNegotiationFailed(error: NfcPresentationError) {
            // The phone left the reader before the handover finished. The
            // user taps again, so the service stays armed.
            if (error == NfcPresentationError.CONNECTION_CLOSED) {
                Log.i(TAG, "Reader tap ended early, still waiting for a tap")
                return
            }
            if (!nfc.onNegotiationFailed()) return
            val generation = nfcGeneration
            coroutineScope.launch {
                if (generation != nfcGeneration) return@launch
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
     *
     * Called from the BLE transport threads. The release touches state that
     * the lifecycle callbacks and cancel() touch on the main thread, so it
     * runs there too.
     */
    private fun releaseNfcAfterSession() {
        if (!nfc.onSessionEnded()) return
        coroutineScope.launch { releaseNfcListening() }
    }

    /** Forgets the attempt and releases the NFC hooks. Main thread only. */
    private fun disarmNfc() {
        nfcGeneration++
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
        try {
            if (CardEmulation.getInstance(adapter).setPreferredService(activity, component)) {
                preferredServiceSet = true
            } else {
                Log.w(TAG, "setPreferredService failed")
            }
        } catch (e: IllegalArgumentException) {
            // The Activity left the resumed state between the check and the call.
            Log.w(TAG, "setPreferredService rejected", e)
        }
    }

    private fun clearPreferredService() {
        if (!preferredServiceSet) return
        preferredServiceSet = false
        val activity = activityBinding?.activity ?: return
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        try {
            if (!CardEmulation.getInstance(adapter).unsetPreferredService(activity)) {
                Log.w(TAG, "unsetPreferredService failed")
            }
        } catch (e: IllegalArgumentException) {
            // Android already dropped the preference with the Activity.
            Log.w(TAG, "unsetPreferredService rejected", e)
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
