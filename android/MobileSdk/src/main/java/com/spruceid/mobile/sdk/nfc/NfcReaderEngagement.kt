package com.spruceid.mobile.sdk.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.spruceid.mobile.sdk.rs.ReaderApduHandoverException
import com.spruceid.mobile.sdk.rs.ReaderApduProgress
import com.spruceid.mobile.sdk.rs.ReaderHandover
import com.spruceid.mobile.sdk.rs.newReaderApduHandoverDriver
import java.io.IOException

/**
 * Drives the reader (verifier) side of ISO 18013-5 NFC engagement.
 *
 * Most integrators should prefer [rememberNfcReaderEngagement] from the
 * Compose helper, which wraps this class with lifecycle binding, activity
 * discovery, and event-to-state translation. This class is the lower-level
 * primitive for non-Compose hosts.
 *
 * Typical lifecycle when used directly:
 *  1. Construct with the hosting [Activity] and an event handler. The
 *     constructor registers an internal receiver for NFC adapter state
 *     changes so [Event.NfcDisabled] / [Event.WaitingForTag] are emitted
 *     when the user toggles NFC in system settings.
 *  2. Call [bindToLifecycle] with the activity's [LifecycleOwner] so
 *     reader mode follows the activity's RESUMED/PAUSED state and the
 *     instance is automatically [release]d on DESTROYED. Or, drive
 *     [start] / [stop] / [release] manually.
 *  3. Toggle [setActive] off when the UI is foreground but not actively
 *     soliciting a tap (e.g. on a different tab). Reader mode stays on
 *     to keep the device in initiator role (suppressing foreign HCE,
 *     wallet pickers, and OS tag dispatchers) but detected taps are
 *     silently swallowed.
 *
 * After [Event.Success] the SDK automatically calls `setActive(false)`
 * so a stray second tap during the post-handover BLE phase does not
 * re-fire the handover. To accept another handover, call `setActive(true)`.
 *
 * Transient failures (tag lost, I/O) are recovered automatically: a
 * [Event.WaitingForTag] follows and the next tap starts a fresh handover.
 * Protocol failures keep reader mode armed too — the next tap retries.
 */
class NfcReaderEngagement(
    private val activity: Activity,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        /** NFC hardware is not present on this device. Terminal. */
        object NfcUnsupported : Event()

        /** NFC adapter exists but is turned off in system settings. */
        object NfcDisabled : Event()

        /** Reader mode is armed and waiting for a holder tap. */
        object WaitingForTag : Event()

        /** A tap has been detected; the APDU exchange is in progress. */
        object Exchanging : Event()

        /**
         * Recoverable failure (tag lost, I/O). A [WaitingForTag] is
         * emitted right after. Safe to ignore — only useful for logging.
         */
        data class TransientError(val cause: Throwable) : Event()

        /**
         * Protocol-level failure. Reader mode stays armed; the next tap
         * starts a fresh handover automatically.
         */
        data class ProtocolError(val cause: Throwable) : Event()

        /** Engagement completed. */
        data class Success(val handover: ReaderHandover) : Event()
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val cameraManager: CameraManager? =
        activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var active: Boolean = true
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var boundOwner: LifecycleOwner? = null
    private var receiverRegistered = false
    private var cameraCallbackRegistered = false
    private val unavailableCameras = mutableSetOf<String>()

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) return
            when (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)) {
                NfcAdapter.STATE_ON -> {
                    // Re-arm reader mode if start() was called before the
                    // user toggled NFC off.
                    if (running) {
                        nfcAdapter?.enableReaderMode(activity, readerCallback, READER_FLAGS, null)
                        emit(Event.WaitingForTag)
                    }
                }
                NfcAdapter.STATE_OFF -> emit(Event.NfcDisabled)
            }
        }
    }

    // Samsung quirk: when the camera is open, NfcService forces NFC active
    // polling off ("setReaderMode: active polling is forced to disable now").
    // When the camera closes, NfcService internally clears the app's reader
    // mode (logs `setReaderMode: uid=1000, packageName: android, flags: 0`
    // followed by `restoreSavedTech`) and falls back to the default tag
    // dispatcher. The next tap then hits the OS overlay (`new tag scanned` /
    // `Unknown tag`) instead of our callback. Re-arm reader mode whenever a
    // camera transitions from in-use back to available.
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            unavailableCameras.add(cameraId)
        }

        override fun onCameraAvailable(cameraId: String) {
            val wasUnavailable = unavailableCameras.remove(cameraId)
            if (!wasUnavailable) return
            if (!running) return
            // NfcService has already finished its setReaderMode(uid=1000) +
            // restoreSavedTech sequence by the time this callback fires; we
            // just need to be on the main thread to re-arm.
            mainHandler.post {
                val adapter = nfcAdapter ?: return@post
                if (running && adapter.isEnabled) {
                    Log.d(TAG, "Camera released; re-arming reader mode")
                    adapter.enableReaderMode(activity, readerCallback, READER_FLAGS, null)
                }
            }
        }
    }

    init {
        if (nfcAdapter != null) {
            activity.registerReceiver(
                adapterStateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            )
            receiverRegistered = true
            cameraManager?.registerAvailabilityCallback(cameraAvailabilityCallback, mainHandler)
            cameraCallbackRegistered = cameraManager != null
        }
    }

    private val readerCallback = NfcAdapter.ReaderCallback { tag: Tag ->
        // Guard against stale invocations that the system may dispatch after
        // disableReaderMode() returns.
        if (!running) {
            Log.d(TAG, "Ignoring tag detection after stop()")
            return@ReaderCallback
        }
        if (!active) {
            Log.d(TAG, "Tag detected but engagement is inactive; swallowing")
            return@ReaderCallback
        }
        emit(Event.Exchanging)
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            emit(Event.WaitingForTag)
            return@ReaderCallback
        }
        try {
            isoDep.connect()
            isoDep.timeout = TRANSCEIVE_TIMEOUT_MS
            // Fresh driver per tap: holders that disconnect mid-handover
            // (e.g. wallet picker) reconnect with a new SELECT, so resumption
            // is not possible.
            val driverInit = newReaderApduHandoverDriver()
            var rapdu = isoDep.transceive(driverInit.initialApdu)
            while (true) {
                when (val progress = driverInit.driver.processRapdu(rapdu)) {
                    is ReaderApduProgress.InProgress -> {
                        rapdu = isoDep.transceive(progress.v1)
                    }
                    is ReaderApduProgress.Done -> {
                        try {
                            isoDep.close()
                        } catch (_: IOException) {
                            // Connection already gone; ignore.
                        }
                        // Mark this engagement as done so subsequent taps are
                        // silently swallowed even if the host hasn't yet
                        // reacted to the Success event. We deliberately do NOT
                        // call stop() here: empirically, fully disabling reader
                        // mode at this point lets the OS wallet picker fire
                        // and puts the reader into weird latent states while
                        // the BLE handoff is still in progress. Keeping reader
                        // mode armed with active=false suppresses both.
                        active = false
                        val handover = progress.v1
                        mainHandler.post { onEvent(Event.Success(handover)) }
                        return@ReaderCallback
                    }
                }
            }
        } catch (e: TagLostException) {
            Log.i(TAG, "Tag lost during handover; awaiting next tap", e)
            emit(Event.TransientError(e))
            emit(Event.WaitingForTag)
        } catch (e: IOException) {
            Log.w(TAG, "I/O during handover; awaiting next tap", e)
            emit(Event.TransientError(e))
            emit(Event.WaitingForTag)
        } catch (e: ReaderApduHandoverException) {
            Log.e(TAG, "Handover protocol error", e)
            emit(Event.ProtocolError(e))
        }
    }

    /**
     * Bind reader mode to a [LifecycleOwner]. Maps RESUMED → [start],
     * PAUSED → [stop], DESTROYED → [release]. Idempotent — calling again
     * replaces any previous binding.
     */
    fun bindToLifecycle(owner: LifecycleOwner) {
        boundOwner?.let { prev ->
            lifecycleObserver?.let { prev.lifecycle.removeObserver(it) }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                Lifecycle.Event.ON_DESTROY -> release()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        lifecycleObserver = observer
        boundOwner = owner
    }

    /**
     * Set whether detected taps should be processed. When false, taps are
     * silently swallowed but reader mode stays on, keeping the device in
     * initiator role (so foreign HCE services, wallet pickers, and OS
     * tag dispatchers stay suppressed) while the host UI is not actively
     * soliciting a tap.
     *
     * The SDK automatically sets active=false after delivering
     * [Event.Success]; call `setActive(true)` to accept another handover.
     */
    fun setActive(active: Boolean) {
        this.active = active
    }

    /**
     * Enable reader mode. If NFC is unsupported or disabled, emits the
     * corresponding event and returns false; callers don't need to check
     * up-front. Idempotent.
     */
    fun start(): Boolean {
        if (running) return true
        val adapter = nfcAdapter ?: run {
            emit(Event.NfcUnsupported)
            return false
        }
        if (!adapter.isEnabled) {
            emit(Event.NfcDisabled)
            return false
        }
        running = true
        adapter.enableReaderMode(activity, readerCallback, READER_FLAGS, null)
        emit(Event.WaitingForTag)
        return true
    }

    /** Disable reader mode. Idempotent. The instance remains usable. */
    fun stop() {
        if (!running) return
        running = false
        nfcAdapter?.disableReaderMode(activity)
    }

    /**
     * Fully tear down: [stop] reader mode, unregister the adapter state
     * receiver, and detach any lifecycle binding. After [release] the
     * instance is unusable. Idempotent.
     */
    fun release() {
        stop()
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(adapterStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            receiverRegistered = false
        }
        if (cameraCallbackRegistered) {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
            cameraCallbackRegistered = false
        }
        unavailableCameras.clear()
        boundOwner?.let { owner ->
            lifecycleObserver?.let { owner.lifecycle.removeObserver(it) }
        }
        boundOwner = null
        lifecycleObserver = null
    }

    private fun emit(event: Event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onEvent(event)
        } else {
            mainHandler.post { onEvent(event) }
        }
    }

    companion object {
        private const val TAG = "NfcReaderEngagement"
        private const val TRANSCEIVE_TIMEOUT_MS = 20_000
        // ISO 18013-5 NFC engagement uses IsoDep (Type 4 Tag) over NFC-A;
        // NFC-B is kept as a hedge.
        //
        // Do NOT add NFC_F / NFC_V / NFC_BARCODE here: empirically, on
        // Samsung the wider tech set causes the "new tag scanned" / OS
        // tag-dispatcher overlay to surface on first tap, even with the
        // camera-availability re-arm active. The extra techs evidently
        // route through a code path the re-arm doesn't catch. Mdoc only
        // needs A; B is harmless.
        //
        // FLAG_READER_SKIP_NDEF_CHECK avoids the platform-level NDEF probe
        // (which is what foreign HCE / wallet pickers latch onto on most
        // OEMs). FLAG_READER_NO_PLATFORM_SOUNDS suppresses the system
        // tag-detected chime that otherwise plays on every tag we see.
        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
