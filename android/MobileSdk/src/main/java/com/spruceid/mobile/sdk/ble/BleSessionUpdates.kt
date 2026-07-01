package com.spruceid.mobile.sdk.ble

/**
 * Single source of truth for the payloads emitted on
 * [com.spruceid.mobile.sdk.BLESessionStateDelegate.update] by the BLE transports.
 *
 * Both [TransportBleCentralClient] (Holder-as-Central) and [TransportBlePeripheralServerHolder]
 * (Holder-as-Peripheral) report the same session lifecycle to the host via these keys.
 */
internal object BleSessionUpdates {

    /** Peer established the GATT connection. */
    fun connected(): Map<String, Any> = mapOf("connected" to "")

    /** Connection ended (peer disconnect or transport-specific termination). */
    fun disconnected(): Map<String, Any> = mapOf("disconnected" to "")

    /** A transport/advertising error the host should surface. */
    fun error(message: String?): Map<String, Any> =
        mapOf("error" to (message ?: "Unknown error"))

    /** A transport error carrying the original throwable as the payload value. */
    fun error(error: Throwable): Map<String, Any> = mapOf("error" to error)

    /** Outbound mDL fully sent. */
    fun success(): Map<String, Any> = mapOf("success" to "")

    /** Incremental outbound mDL send progress. */
    fun uploadProgress(progress: Int, max: Int): Map<String, Any> =
        mapOf("uploadProgress" to mapOf("curr" to progress, "max" to max))

    /** Inbound mDL response received (Reader role). */
    fun mdl(data: ByteArray): Map<String, Any> = mapOf("mdl" to data)

    /** Scan/connection attempt timed out. */
    fun timeout(): Map<String, Any> = mapOf("timeout" to "")

    /** Scan was throttled by the system and a retry is pending (transient hint). */
    fun scanThrottled(): Map<String, Any> = mapOf("scan_throttled" to "")

    /** Whether an outbound mDL send has finished (all bytes written). */
    fun isComplete(progress: Int, max: Int): Boolean = progress == max
}
