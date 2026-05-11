package com.spruceid.mobile.sdk.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spruceid.mobile.sdk.rs.ReaderApduHandoverException
import com.spruceid.mobile.sdk.rs.ReaderApduProgress
import com.spruceid.mobile.sdk.rs.ReaderHandover
import com.spruceid.mobile.sdk.rs.newReaderApduHandoverDriver
import java.io.IOException

/**
 * Drives the reader (verifier) side of ISO 18013-5 NFC engagement.
 *
 * Lifecycle (Activity-scoped):
 *  1. Construct with the hosting [Activity] and an event handler.
 *  2. Call [start] when the user is ready to tap (e.g. when a verifier
 *     screen becomes active). [start] enables NFC reader mode and emits
 *     [Event.WaitingForTag].
 *  3. On tap, the APDU exchange runs on a binder thread; events are always
 *     delivered to the consumer on the main thread.
 *  4. Call [stop] when leaving the screen. After [Event.Success] reader
 *     mode is intentionally NOT released — only [engageOnTap] is flipped
 *     to false. Callers typically keep the device in initiator role
 *     through the post-handover BLE phase so foreign HCE services,
 *     wallet pickers, and OS tag dispatchers stay suppressed while the
 *     phones may still be in proximity.
 *
 * Transient failures (tag lost, I/O) are recovered automatically: reader
 * mode stays on and the next tap starts a fresh handover. This handles
 * holders that disconnect after the first APDU to show a wallet picker
 * and reconnect (e.g. Samsung Wallet).
 */
class NfcReaderEngagement(
    private val activity: Activity,
    private val onEvent: (Event) -> Unit,
) {
    sealed class Event {
        object WaitingForTag : Event()
        object Exchanging : Event()
        /** Recoverable failure (tag lost, I/O). Reader mode is still active. */
        data class TransientError(val cause: Throwable) : Event()
        /** Protocol-level failure. Caller should decide whether to [start] again. */
        data class ProtocolError(val cause: Throwable) : Event()
        data class Success(val handover: ReaderHandover) : Event()
    }

    val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isSupported: Boolean get() = nfcAdapter != null
    val isEnabled: Boolean get() = nfcAdapter?.isEnabled == true

    /**
     * When false, detected tags are silently consumed without an APDU exchange
     * or any [Event] emission. Reader mode itself stays enabled, which is
     * useful for keeping the device in initiator role (so foreign HCE,
     * system tag dispatch, OEM "tag scanner" overlays, etc. are suppressed)
     * while the host UI is foreground but not actively soliciting a tap.
     */
    @Volatile
    var engageOnTap: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private val readerCallback = NfcAdapter.ReaderCallback { tag: Tag ->
        // Guard against stale invocations that the system may dispatch after
        // disableReaderMode() returns.
        if (!running) {
            Log.d(TAG, "Ignoring tag detection after stop()")
            return@ReaderCallback
        }
        if (!engageOnTap) {
            Log.d(TAG, "Tag detected but engagement is paused; swallowing")
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
                        engageOnTap = false
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
     * Enable reader mode. Returns true if reader mode was activated.
     * Returns false if NFC is unsupported or disabled — callers should
     * check [isSupported] / [isEnabled] beforehand to give a useful UI.
     */
    fun start(): Boolean {
        if (running) return true
        val adapter = nfcAdapter ?: return false
        if (!adapter.isEnabled) return false
        running = true
        adapter.enableReaderMode(activity, readerCallback, READER_FLAGS, null)
        emit(Event.WaitingForTag)
        return true
    }

    /** Disable reader mode. Idempotent. */
    fun stop() {
        if (!running) return
        running = false
        nfcAdapter?.disableReaderMode(activity)
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
        // by the callback (when engageOnTap=false or non-IsoDep), but we MUST
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
