package com.spruceid.mobile.sdk.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var active: Boolean = true
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var boundOwner: LifecycleOwner? = null
    private var receiverRegistered = false

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

    init {
        if (nfcAdapter != null) {
            activity.registerReceiver(
                adapterStateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            )
            receiverRegistered = true
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
                        // call stop() here: callers typically want reader
                        // mode to stay active through the post-handover BLE
                        // phase so the device stays in initiator role and no
                        // foreign HCE service / OS tag dispatcher gets fired
                        // while the phones may still be in proximity.
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
        // Claim every NFC tech: tags we don't recognise are silently swallowed
        // by the callback (when active=false or non-IsoDep), but we MUST
        // claim them at the reader-mode level so the OS doesn't fall through
        // to its default tag dispatcher (TECH_DISCOVERED → system TagViewer,
        // OEM "tag scanner" overlays, NDEF auto-launch, etc.). Observed in
        // the wild: Pixel + Google Wallet emits NFC-F during ISO 18013-5
        // engagement; without FLAG_READER_NFC_F our reader mode misses it
        // and the OS launches com.android.apps.tag/.TagViewer.
        // FLAG_READER_NO_PLATFORM_SOUNDS also kills the system tag-detected
        // chime, which otherwise plays even on tags we silently swallow.
        //
        // Known limitation on Samsung: when a holder advertises NDEF over
        // HCE (Google Wallet does this), Samsung's modified NfcService runs
        // its own NDEF read and dispatches the tag explicitly to
        // com.android.apps.tag/.TagViewer, bypassing both this reader mode
        // (despite FLAG_READER_SKIP_NDEF_CHECK) and any manifest
        // TECH_DISCOVERED intent-filter. The dispatch uses an explicit
        // component, so no app-side filter can intercept it. The first tap
        // shows a brief "Unknown tag" overlay; subsequent taps within the
        // same engagement go through reader mode normally.
        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
