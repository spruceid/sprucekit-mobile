package com.spruceid.mobile.sdk.ble

import com.spruceid.mobile.sdk.BLESessionStateDelegate
import org.junit.Assert.*
import org.junit.Test

class BleSessionEmitterTest {
    
    private class RecordingDelegate : BLESessionStateDelegate() {
        val updates = mutableListOf<Map<String, Any>>()
        val errors = mutableListOf<Exception>()
        override fun update(state: Map<String, Any>) { updates.add(state) }
        override fun error(error: Exception) { errors.add(error) }
    }

    @Test
    fun connectedDeliversConnectedPayload() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).connected()
        assertEquals(listOf(mapOf("connected" to "")), d.updates)
    }

    @Test
    fun disconnectedDeliversDisconnectedPayload() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).disconnected()
        assertEquals(listOf(mapOf("disconnected" to "")), d.updates)
    }

    @Test
    fun errorMessageDeliversErrorPayload() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).error("scan failed")
        assertEquals(listOf(mapOf("error" to "scan failed")), d.updates)
    }

    @Test
    fun errorNullMessageDeliversFallback() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).error(null as String?)
        assertEquals(listOf(mapOf("error" to "Unknown error")), d.updates)
    }

    /** The Throwable overload preserves the throwable as the payload value. */
    @Test
    fun errorThrowableDeliversThrowablePayload() {
        val d = RecordingDelegate()
        val boom = IllegalStateException("kaboom")
        BleSessionEmitter(d).error(boom)
        assertEquals(listOf(mapOf<String, Any>("error" to boom)), d.updates)
    }

    @Test
    fun successDeliversSuccessPayload() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).success()
        assertEquals(listOf(mapOf("success" to "")), d.updates)
    }

    @Test
    fun uploadProgressDeliversCurrAndMax() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).uploadProgress(30, 100)
        assertEquals(
            listOf(mapOf("uploadProgress" to mapOf("curr" to 30, "max" to 100))),
            d.updates
        )
    }

    /** sendProgress convenience: completion -> success, else uploadProgress. */
    @Test
    fun sendProgressAtMaxDeliversSuccess() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).sendProgress(100, 100)
        assertEquals(listOf(mapOf("success" to "")), d.updates)
    }

    @Test
    fun sendProgressBelowMaxDeliversUploadProgress() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).sendProgress(40, 100)
        assertEquals(
            listOf(mapOf("uploadProgress" to mapOf("curr" to 40, "max" to 100))),
            d.updates
        )
    }

    @Test
    fun mdlDeliversResponseBytes() {
        val d = RecordingDelegate()
        val bytes = byteArrayOf(1, 2, 3)
        BleSessionEmitter(d).mdl(bytes)
        assertEquals(1, d.updates.size)
        assertSame(bytes, d.updates[0]["mdl"])
    }

    @Test
    fun timeoutDeliversTimeoutPayload() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).timeout()
        assertEquals(listOf(mapOf("timeout" to "")), d.updates)
    }

    @Test
    fun scanThrottledDeliversScanThrottledPayload() {
        val d = RecordingDelegate()
        BleSessionEmitter(d).scanThrottled()
        assertEquals(listOf(mapOf("scan_throttled" to "")), d.updates)
    }

    // --- ordering + null safety ---

    @Test
    fun fullHolderSequenceDeliveredInOrder() {
        val d = RecordingDelegate()
        val e = BleSessionEmitter(d)
        e.connected()
        e.sendProgress(50, 100)
        e.sendProgress(100, 100)
        e.disconnected()
        assertEquals(
            listOf(
                mapOf("connected" to ""),
                mapOf("uploadProgress" to mapOf("curr" to 50, "max" to 100)),
                mapOf("success" to ""),
                mapOf("disconnected" to "")
            ),
            d.updates
        )
        assertTrue(d.errors.isEmpty())
    }

    @Test
    fun nullDelegateIsNoOp() {
        val e = BleSessionEmitter(null)
        e.connected()
        e.disconnected()
        e.error("x")
        e.error(RuntimeException("y"))
        e.success()
        e.uploadProgress(1, 2)
        e.sendProgress(2, 2)
        e.mdl(byteArrayOf(0))
        e.timeout()
        e.scanThrottled()
        // Reaching here without throwing is the assertion.
    }
}
