package com.spruceid.mobile.sdk.ble

import com.spruceid.mobile.sdk.BLESessionStateDelegate
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TransportTest {

    @Before
    fun setUp() {
        mockAndroidLog()
        BleConnectionStateMachine.resetInstance(BleConnectionStateMachineInstanceType.CLIENT)
    }

    @After
    fun tearDown() {
        BleConnectionStateMachine.resetInstance(BleConnectionStateMachineInstanceType.CLIENT)
        unmockkAll()
    }

    /** A dropped response must reach the session delegate, or the holder waits forever. */
    @Test
    fun sendWhileNotConnectedReportsAnError() {
        val updates = mutableListOf<Map<String, Any>>()
        val transport = Transport(mockk(relaxed = true), mockk(relaxed = true))
        // A retrieval method other than "BLE" keeps the callback without starting BLE.
        transport.initialize(
            application = "Holder",
            serviceUUID = UUID.randomUUID(),
            deviceRetrieval = "NONE",
            deviceRetrievalOption = "Central",
            ident = byteArrayOf(),
            callback = object : BLESessionStateDelegate() {
                override fun update(state: Map<String, Any>) {
                    updates.add(state)
                }

                override fun error(error: Exception) {}
            },
        )

        transport.send(byteArrayOf(0x01))

        assertEquals(1, updates.size)
        assertTrue(updates.single().containsKey("error"))
    }
}
