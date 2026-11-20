package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Tests how [GattServer] in the Holder role holds and releases the link to a reader. The test
 * drives the GATT server callbacks that Android would call, against a mocked
 * [BluetoothGattServer].
 *
 * Regression: in dual mode, the holder's GATT client can connect to the reader on the same link
 * as the reader's connection to this server. When the reader's request arrives through the
 * server, the holder closes the unused client. Android counted only the client as a user of the
 * link, so it dropped the link 1 second later, and the reader got no response.
 */
class GattServerConnectionTest {

    private val context = mockk<Context>(relaxed = true)
    private val server = mockk<BluetoothGattServer>(relaxed = true)
    private val reader = device("00:81:2A:8E:7D:00")

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

    /** The server holds the link as soon as the reader connects, before any request. */
    @Test
    fun holdsTheLinkWhenTheReaderConnects() {
        val gattServer = gattServer()

        connected(gattServer, reader)

        verify(exactly = 1) { server.connect(reader, false) }
    }

    /** Android can report the connection again after `connect`; that must not loop. */
    @Test
    fun holdsTheLinkOnlyOnce() {
        val gattServer = gattServer()

        connected(gattServer, reader)
        connected(gattServer, reader)

        verify(exactly = 1) { server.connect(reader, false) }
    }

    /** A reader that disconnects and connects again is held again. */
    @Test
    fun holdsTheLinkAgainAfterAReconnect() {
        val gattServer = gattServer()

        connected(gattServer, reader)
        disconnected(gattServer, reader)
        connected(gattServer, reader)

        verify(exactly = 2) { server.connect(reader, false) }
    }

    /** A failed hold is tried again on the next connection event. */
    @Test
    fun triesAgainAfterAFailedHold() {
        val gattServer = gattServer()
        every { server.connect(reader, false) } returns false andThen true

        connected(gattServer, reader)
        connected(gattServer, reader)

        verify(exactly = 2) { server.connect(reader, false) }
    }

    /** Stop releases a held link even when the reader never sent START. */
    @Test
    fun stopReleasesTheLinkOfAReaderThatNeverStarted() {
        val gattServer = gattServer()
        connected(gattServer, reader)

        gattServer.stop()

        verify(exactly = 1) { server.cancelConnection(reader) }
        verify { server.close() }
    }

    /** A reader that sent START is both held and current, and is released once. */
    @Test
    fun stopReleasesAStartedReaderOnce() {
        val gattServer = gattServer()
        connected(gattServer, reader)
        start(gattServer, reader)

        gattServer.stop()

        verify(exactly = 1) { server.cancelConnection(reader) }
    }

    /** A link that Android already dropped is not released again at stop. */
    @Test
    fun stopDoesNotReleaseADroppedLink() {
        val gattServer = gattServer()
        connected(gattServer, reader)
        disconnected(gattServer, reader)

        gattServer.stop()

        verify(exactly = 0) { server.cancelConnection(any()) }
    }

    private fun gattServer(): GattServer {
        val gattServer = GattServer(object : GattServerCallback() {}, UUID.randomUUID())
        gattServer.gattServer = server
        return gattServer
    }

    private fun device(address: String): BluetoothDevice {
        val device = mockk<BluetoothDevice>(relaxed = true)
        every { device.address } returns address
        return device
    }

    private fun connected(gattServer: GattServer, device: BluetoothDevice) {
        gattServer.bluetoothGattServerCallback.onConnectionStateChange(
            device, 0, BluetoothProfile.STATE_CONNECTED
        )
    }

    private fun disconnected(gattServer: GattServer, device: BluetoothDevice) {
        gattServer.bluetoothGattServerCallback.onConnectionStateChange(
            device, 0, BluetoothProfile.STATE_DISCONNECTED
        )
    }

    /** The reader writes START (0x01) to the state characteristic. */
    private fun start(gattServer: GattServer, device: BluetoothDevice) {
        val state = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { state.uuid } returns BleConstants.Holder.STATE_UUID
        gattServer.bluetoothGattServerCallback.onCharacteristicWriteRequest(
            device, 1, state, false, false, 0, byteArrayOf(0x01)
        )
    }
}
