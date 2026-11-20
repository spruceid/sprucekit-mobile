package com.spruceid.mobile.sdk.ble

import com.spruceid.mobile.sdk.BLESessionStateDelegate

/**
 * Forwards BLE transport events to the host [BLESessionStateDelegate], building payloads via
 * [BleSessionUpdates]. Shared by both transports — [TransportBleCentralClient] and
 * [TransportBlePeripheralServerHolder] — so neither calls `callback.update(...)` directly and
 * the two roles report an identical event vocabulary.
 *
 * A null delegate is tolerated (no-op), matching the transports' optional callbacks.
 */
internal class BleSessionEmitter(private val callback: BLESessionStateDelegate?) {

    /** Peer connected. */
    fun connected() {
        callback?.update(BleSessionUpdates.connected())
    }

    /** Connection ended (peer disconnect or transport-specific termination). */
    fun disconnected() {
        callback?.update(BleSessionUpdates.disconnected())
    }

    /** Transport/advertising error (message form). */
    fun error(message: String?) {
        callback?.update(BleSessionUpdates.error(message))
    }

    /** Transport error carrying the original throwable. */
    fun error(error: Throwable) {
        callback?.update(BleSessionUpdates.error(error))
    }

    /** Outbound mDL fully sent. */
    fun success() {
        callback?.update(BleSessionUpdates.success())
    }

    /** Incremental outbound mDL send progress. */
    fun uploadProgress(progress: Int, max: Int) {
        callback?.update(BleSessionUpdates.uploadProgress(progress, max))
    }

    /**
     * Outbound send tick for callers that always signal completion (e.g. the peripheral holder,
     * which is always a Holder): terminal [success] when complete, else [uploadProgress].
     * Callers that gate success on role (the Central client's Reader role) call
     * [success]/[uploadProgress] directly.
     */
    fun sendProgress(progress: Int, max: Int) {
        callback?.update(
            if (BleSessionUpdates.isComplete(progress, max)) {
                BleSessionUpdates.success()
            } else {
                BleSessionUpdates.uploadProgress(progress, max)
            }
        )
    }

    /** Inbound mDL response received (Reader role). */
    fun mdl(data: ByteArray) {
        callback?.update(BleSessionUpdates.mdl(data))
    }

    /** Scan/connection attempt timed out. */
    fun timeout() {
        callback?.update(BleSessionUpdates.timeout())
    }

    /** Scan throttled by the system; retry pending. */
    fun scanThrottled() {
        callback?.update(BleSessionUpdates.scanThrottled())
    }
}
