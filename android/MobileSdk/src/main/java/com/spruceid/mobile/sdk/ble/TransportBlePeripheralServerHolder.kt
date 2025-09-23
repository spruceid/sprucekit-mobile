package com.spruceid.mobile.sdk.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.*

/**
 * The responsibility of this class is to advertise data and be available for connection. AKA Holder.
 * 18013-5 section 8.3.3.1.1.4 Table 12.
 */
class TransportBlePeripheralServerHolder(
    private var application: String,
    private var serviceUUID: UUID
) {

    private val stateMachine = BleConnectionStateMachine.getInstance()
    private var bluetoothAdapter: BluetoothAdapter = stateMachine.getBluetoothManager().adapter
    private var context: Context = stateMachine.getContext()

    private lateinit var previousAdapterName: String
    private lateinit var blePeripheral: BlePeripheral
    private lateinit var gattServer: GattServer

    /**
     * Sets up peripheral with GATT server mode.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start() {
        // Transition to connecting state
        if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTING)) {
            Log.w("TransportBlePeripheralServerHolder.start", "Failed to transition to CONNECTING state")
        }

        /**
         * BLE Peripheral callback.
         */
        val blePeripheralCallback: BlePeripheralCallback = object : BlePeripheralCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}

            override fun onStartFailure(errorCode: Int) {}

            override fun onState(state: String) {
                Log.d("TransportBlePeripheralServerHolder.blePeripheralCallback.onState", state)
            }
        }

        /**
         * GATT server callback.
         */
        val gattServerCallback: GattServerCallback = object : GattServerCallback() {
            override fun onPeerConnected() {
                Log.d(
                    "TransportBlePeripheralServerHolder.gattServerCallback.onPeerConnected",
                    "Peer Connected"
                )
                // Transition to connected state
                if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)) {
                    Log.w("TransportBlePeripheralServerHolder.gattServerCallback.onPeerConnected", "Failed to transition to CONNECTED state")
                }
            }

            override fun onPeerDisconnected() {
                Log.d(
                    "TransportBlePeripheralServerHolder.gattServerCallback.onPeerDisconnected",
                    "Peer Disconnected"
                )
                // Transition to disconnected state
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                gattServer.stop()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {
                Log.d(
                    "TransportBlePeripheralServerHolder.gattServerCallback.onMessageSendProgress",
                    "progress:$progress max:$max"
                )

                blePeripheral.stopAdvertise()
            }

            override fun onTransportSpecificSessionTermination() {
                // Transition to disconnected state on termination
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                gattServer.stop()
            }

            override fun onLog(message: String) {
                Log.d("TransportBlePeripheralServerHolder.gattServerCallback.onLog", message)
            }

            override fun onState(state: String) {
                Log.d("TransportBlePeripheralServerHolder.gattServerCallback.onState", state)
            }
        }

        /**
         * Setting up device name for easier identification after connection - too large to be in
         * advertisement data.
         */
        try {
            if (bluetoothAdapter.name != null) {
                previousAdapterName = bluetoothAdapter.name
                bluetoothAdapter.name = "mDL $application Device"
            }
        } catch (error: SecurityException) {
            Log.e("TransportBlePeripheralServerHolder.start", error.toString())
        }

        gattServer = GattServer(
            gattServerCallback,
            serviceUUID,
            false
        )

        blePeripheral = BlePeripheral(blePeripheralCallback, serviceUUID, bluetoothAdapter)
        try {
            blePeripheral.advertise()
            gattServer.start(null)
        } catch (error: Exception) {
            Log.e("TransportBlePeripheralServerHolder.start", error.toString())
            stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            throw error
        }
    }

    /**
     * For sending the mDL.
     */
    fun send(payload: ByteArray) {
        gattServer.sendMessage(payload)
    }

    fun stop() {
        // Transition to disconnecting state
        if (stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTING)) {
            if (this::previousAdapterName.isInitialized) {
                try {
                    bluetoothAdapter.name = previousAdapterName
                } catch (error: SecurityException) {
                    Log.e("TransportBlePeripheralServerHolder.stop", error.toString())
                }
            }

            gattServer.sendTransportSpecificTermination()
            blePeripheral.stopAdvertise()
            gattServer.stop()

            // Transition to disconnected state
            stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
        } else {
            Log.w("TransportBlePeripheralServerHolder.stop", "Failed to transition to DISCONNECTING state")
        }
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        blePeripheral.stopAdvertise()
        gattServer.stop()
        gattServer.reset()

        // Force reset to idle state
        stateMachine.reset()
    }
}
