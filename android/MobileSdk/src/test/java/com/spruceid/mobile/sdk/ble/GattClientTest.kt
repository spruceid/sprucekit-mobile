package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.spruceid.mobile.sdk.ble.BleConnectionStateMachine.State
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Tests [GattClient] in the Holder role against a mocked [BluetoothGatt]. The test drives the
 * GATT callbacks that Android would call.
 */
class GattClientTest {

    private lateinit var stateMachine: BleConnectionStateMachine
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        mockAndroidLog()
        BleConnectionStateMachine.resetInstance(BleConnectionStateMachineInstanceType.CLIENT)
        stateMachine =
            BleConnectionStateMachine.getInstance(BleConnectionStateMachineInstanceType.CLIENT)
        // The state machine keeps only a weak reference to the application context.
        every { context.applicationContext } returns context
        stateMachine.start(mockk(relaxed = true), context)
    }

    @After
    fun tearDown() {
        BleConnectionStateMachine.resetInstance(BleConnectionStateMachineInstanceType.CLIENT)
        unmockkAll()
    }

    /**
     * The holder reports completion, which becomes the `success` session update, only when
     * Android confirms the write of the last chunk.
     */
    @Test
    fun reportsCompletionOnlyAfterTheLastChunkIsWritten() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val client = GattClient(
            object : GattClientCallback() {
                override fun onMessageSendProgress(progress: Int, max: Int) {
                    reported.add(progress to max)
                }
            },
            UUID.randomUUID(),
            isReader = false,
        )

        val written = mutableListOf<ByteArray>()
        val client2Server = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { client2Server.uuid } returns client.client2ServerUuid
        // JVM tests see SDK_INT 0, so GattClient uses the pre-API 33 write calls.
        @Suppress("DEPRECATION")
        every { client2Server.setValue(any<ByteArray>()) } answers {
            written.add(firstArg())
            true
        }
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        @Suppress("DEPRECATION")
        every { gatt.writeCharacteristic(client2Server) } returns true
        val gattCallback = slot<BluetoothGattCallback>()
        val device = mockk<BluetoothDevice>(relaxed = true)
        every { device.connectGatt(any(), any(), capture(gattCallback), any()) } returns gatt

        client.connect(device, null)
        client.characteristicClient2Server = client2Server
        connectStateMachine()

        // MTU 23 gives 19 payload bytes per chunk: 17 full chunks and 1 of 17 bytes.
        val chunks = 18
        client.sendMessage(ByteArray(340) { it.toByte() })

        repeat(chunks) { i ->
            assertEquals("one chunk in flight at a time", i + 1, written.size)
            assertTrue("completion before chunk ${i + 1} was written", reported.none { it.first == it.second })
            gattCallback.captured.onCharacteristicWrite(
                gatt,
                client2Server,
                BluetoothGatt.GATT_SUCCESS
            )
        }

        assertEquals(chunks to chunks, reported.last())
        assertEquals(1, reported.count { it.first == it.second })
        assertTrue(written.dropLast(1).all { it[0] == 0x01.toByte() })
        assertEquals(0x00.toByte(), written.last()[0])
    }

    /** Transport.terminate() moves the state machine to DISCONNECTING before it calls disconnect(). */
    @Test
    fun disconnectClosesGattWhenTerminateAlreadyStartedDisconnecting() {
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        val client = GattClient(object : GattClientCallback() {}, UUID.randomUUID(), isReader = false)
        client.gattClient = gatt
        connectStateMachine()

        terminateLikeTransport(client)

        verify { gatt.close() }
    }

    /**
     * A GATT client from a terminated session must not end the next session. If its GATT stays
     * open, it receives the next session's request and disconnects through the shared state
     * machine. The next session then cannot send its response.
     */
    @Test
    fun previousSessionDoesNotDisconnectTheNextOne() {
        val previous = GattClient(object : GattClientCallback() {}, UUID.randomUUID(), isReader = false)
        previous.gattClient = mockk(relaxed = true)
        connectStateMachine()
        terminateLikeTransport(previous)

        connectStateMachine()
        // What the previous session does when it gets the next session's request.
        previous.disconnect()

        assertEquals(State.CONNECTED, stateMachine.getState())
    }

    private fun connectStateMachine() {
        assertTrue(stateMachine.transitionTo(State.CONNECTING))
        assertTrue(stateMachine.transitionTo(State.CONNECTED))
    }

    /** The order of [Transport.terminate]. */
    private fun terminateLikeTransport(client: GattClient) {
        assertTrue(stateMachine.transitionTo(State.DISCONNECTING))
        client.disconnect()
        stateMachine.transitionTo(State.DISCONNECTED)
    }
}
