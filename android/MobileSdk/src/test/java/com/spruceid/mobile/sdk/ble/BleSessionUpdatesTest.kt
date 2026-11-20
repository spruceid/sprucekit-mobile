package com.spruceid.mobile.sdk.ble

import org.junit.Assert.*
import org.junit.Test

class BleSessionUpdatesTest {

    @Test
    fun connectedEmitsConnectedKey() {
        assertEquals(mapOf("connected" to ""), BleSessionUpdates.connected())
    }

    @Test
    fun disconnectedEmitsDisconnectedKey() {
        assertEquals(mapOf("disconnected" to ""), BleSessionUpdates.disconnected())
    }

    @Test
    fun errorUsesProvidedMessage() {
        assertEquals(mapOf("error" to "boom"), BleSessionUpdates.error("boom"))
    }

    @Test
    fun errorFallsBackWhenMessageNull() {
        assertEquals(mapOf("error" to "Unknown error"), BleSessionUpdates.error(null))
    }

    @Test
    fun successEmitsSuccessKey() {
        assertEquals(mapOf("success" to ""), BleSessionUpdates.success())
    }

    @Test
    fun uploadProgressCarriesCurrAndMax() {
        assertEquals(
            mapOf("uploadProgress" to mapOf("curr" to 40, "max" to 100)),
            BleSessionUpdates.uploadProgress(40, 100)
        )
    }

    @Test
    fun isCompleteWhenProgressEqualsMax() {
        assertTrue(BleSessionUpdates.isComplete(100, 100))
    }

    @Test
    fun isNotCompleteBelowMax() {
        assertFalse(BleSessionUpdates.isComplete(40, 100))
        assertFalse(BleSessionUpdates.isComplete(0, 100))
    }
    
    @Test
    fun successPayloadHasNoProgressFields() {
        assertFalse(BleSessionUpdates.success().containsKey("uploadProgress"))
    }
}
