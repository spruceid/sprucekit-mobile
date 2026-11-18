package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.content.Context
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Tests that a reader learns when the holder ends the session before it responds, for example
 * because the user declined. The holder sends the session termination (0x02 on the State
 * characteristic) and disconnects.
 *
 * Regression: the reader's GATT server only logged the termination and held the link, so the
 * reader waited for a response that never came.
 */
class ReaderSessionEndTest {

    private class RecordingDelegate : BLESessionStateDelegate() {
        val updates = mutableListOf<Map<String, Any>>()
        override fun update(state: Map<String, Any>) { updates.add(state) }
        override fun error(error: Exception) {}
    }

    private val delegate = RecordingDelegate()
    private val sessionEnd = ReaderSessionEnd(BleSessionEmitter(delegate))

    private val context = mockk<Context>(relaxed = true)
    private val server = mockk<BluetoothGattServer>(relaxed = true)
    private val holder = device("00:81:2A:8E:7D:00")

    @Before
    fun setUp() {
        mockAndroidLog()
        BleConnectionStateMachine.resetInstance(BleConnectionStateMachineInstanceType.SERVER)
        every { context.applicationContext } returns context
        BleConnectionStateMachine.getInstance(BleConnectionStateMachineInstanceType.SERVER)
            .start(mockk(relaxed = true), context)
        every { server.connect(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        BleConnectionStateMachine.resetInstance(BleConnectionStateMachineInstanceType.SERVER)
        unmockkAll()
    }

    @Test
    fun sessionEndedPayload() {
        assertEquals(mapOf("sessionEnded" to ""), BleSessionUpdates.sessionEnded())
    }

    /** The holder ends the session while the reader waits: reported as the outcome. */
    @Test
    fun reportsTheHolderEndingTheSessionBeforeTheResponse() {
        sessionEnd.holderConnected()

        assertTrue(sessionEnd.holderEnded())

        assertEquals(listOf(BleSessionUpdates.sessionEnded()), delegate.updates)
    }

    /** The termination and the disconnect that follows it are reported once. */
    @Test
    fun reportsTheSessionEndOnce() {
        sessionEnd.holderConnected()

        sessionEnd.holderEnded()
        assertFalse(sessionEnd.holderEnded())

        assertEquals(listOf(BleSessionUpdates.sessionEnded()), delegate.updates)
    }

    /** After the response, the termination is the normal end of the session. */
    @Test
    fun doesNotReportTheSessionEndAfterTheResponse() {
        sessionEnd.holderConnected()
        sessionEnd.responseReceived()

        assertFalse(sessionEnd.holderEnded())

        assertTrue(delegate.updates.isEmpty())
    }

    /** Before a holder connected there is no session to end. */
    @Test
    fun doesNotReportASessionEndBeforeTheHolderConnected() {
        assertFalse(sessionEnd.isHolderConnected)
        assertFalse(sessionEnd.holderEnded())

        assertTrue(delegate.updates.isEmpty())
    }

    /** The reader's GATT server passes a 0x02 on the State characteristic on as a termination. */
    @Test
    fun readerGattServerForwardsTheHolderTermination() {
        var terminations = 0
        val gattServer = GattServer(
            object : GattServerCallback() {
                override fun onPeerConnected() {
                    sessionEnd.holderConnected()
                }

                override fun onTransportSpecificSessionTermination() {
                    terminations++
                    sessionEnd.holderEnded()
                }
            },
            UUID.randomUUID(),
            true,
        )
        gattServer.gattServer = server

        writeState(gattServer, 0x01)
        writeState(gattServer, 0x02)

        assertEquals(1, terminations)
        assertEquals(listOf(BleSessionUpdates.sessionEnded()), delegate.updates)
    }

    private fun device(address: String): BluetoothDevice {
        val device = mockk<BluetoothDevice>(relaxed = true)
        every { device.address } returns address
        return device
    }

    /** The holder writes [value] to the reader's State characteristic. */
    private fun writeState(gattServer: GattServer, value: Int) {
        val state = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { state.uuid } returns BleConstants.Reader.STATE_UUID
        gattServer.bluetoothGattServerCallback.onCharacteristicWriteRequest(
            holder, 1, state, false, false, 0, byteArrayOf(value.toByte())
        )
    }
}
