package com.spruceid.mobile.sdk.ble

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells the reader's host when the holder ends the session before it sends a response.
 *
 * A holder that declines a request ends the session with the transport-specific session
 * termination (0x02 on the State characteristic, ISO 18013-5 section 8.3.3.1.1.7) and then
 * disconnects. Without this, a reader that is waiting for the response keeps waiting: the
 * termination is only logged, and a GATT server reader holds the link, so the disconnect may never
 * arrive either.
 *
 * Both signals call [holderEnded]. The host gets [BleSessionUpdates.sessionEnded] once, and only
 * if a holder had connected and no response has arrived. After a response, the same signals are
 * the normal end of the session and are not reported.
 *
 * The BLE callbacks run on binder threads, so the state is atomic.
 */
internal class ReaderSessionEnd(private val emitter: BleSessionEmitter) {
    private val connected = AtomicBoolean(false)
    private val outcomeReported = AtomicBoolean(false)

    /** Whether a holder connected and started the session. */
    val isHolderConnected: Boolean
        get() = connected.get()

    /** A holder connected and started the session. */
    fun holderConnected() {
        connected.set(true)
    }

    /** The response arrived; a later session end is the normal close. */
    fun responseReceived() {
        outcomeReported.set(true)
    }

    /**
     * The holder sent the session termination or disconnected. Reports the session end if it
     * comes before the response.
     *
     * @return true if the session end was reported.
     */
    fun holderEnded(): Boolean {
        if (!connected.get()) return false
        if (!outcomeReported.compareAndSet(false, true)) return false
        emitter.sessionEnded()
        return true
    }
}
