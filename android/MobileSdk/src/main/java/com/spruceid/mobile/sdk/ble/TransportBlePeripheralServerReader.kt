/**
 * TransportBlePeripheralServerReader.kt
 *
 * SPRUCE SYSTEMS, INC. PROPRIETARY AND CONFIDENTIAL.
 *
 * Spruce Systems, Inc. Copyright 2023-2024. All Rights Reserved. Spruce Systems,
 * Inc.  retains sole and exclusive, right, title and interest in and to all code,
 * Work Product and other deliverables, and all copies, modifications, and
 * derivative works thereof, including all proprietary or intellectual property
 * rights contained therein. The file may not be used or distributed without
 * express permission of Spruce Systems, Inc.
 */

package com.spruceid.mobile.sdk.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import java.util.*

/**
 * The responsibility of this class is to advertise data and be available for connection. AKA Reader.
 * 18013-5 section 8.3.3.1.1.4 Table 11.
 */
class TransportBlePeripheralServerReader(
    private val callback: BLESessionStateDelegate?,
    private var application: String,
    private var serviceUUID: UUID
) {
    private val stateMachine = BleConnectionStateMachine.getInstance()
    private var bluetoothAdapter: BluetoothAdapter = stateMachine.getBluetoothManager().adapter
    private val context: Context = stateMachine.getContext()

    private lateinit var previousAdapterName: String
    private lateinit var blePeripheral: BlePeripheral
    private lateinit var gattServer: GattServer
    private lateinit var identValue: ByteArray

    /**
     * Sets up peripheral with GATT server mode.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(ident: ByteArray, encodedEDeviceKeyBytes: ByteArray) {
        // Transition to connecting state
        if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTING)) {
            Log.w("TransportBlePeripheralServerReader.start", "Failed to transition to CONNECTING state")
        }

        /**
         * Should be generated based on the 18013-5 section 8.3.3.1.1.3.
         */
        identValue = ident

        /**
         * BLE Peripheral callback.
         */
        val blePeripheralCallback: BlePeripheralCallback = object : BlePeripheralCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
            override fun onStartFailure(errorCode: Int) {}

            override fun onLog(message: String) {
                Log.d("TransportBlePeripheralServerReader.blePeripheralCallback.onLog", message)
            }

            override fun onState(state: String) {
                Log.d("TransportBlePeripheralServerReader.blePeripheralCallback.onState", state)
            }
        }

        /**
         * GATT server callback.
         */
        val gattServerCallback: GattServerCallback = object : GattServerCallback() {
            override fun onPeerConnected() {
                // Transition to connected state
                if (stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)) {
                    blePeripheral.stopAdvertise()
                    gattServer.sendMessage(encodedEDeviceKeyBytes)

                    Log.d(
                        "TransportBlePeripheralServerReader.gattCallback.onPeerConnected",
                        "Peer connected"
                    )
                } else {
                    Log.w("TransportBlePeripheralServerReader.gattCallback.onPeerConnected", "Failed to transition to CONNECTED state")
                }
            }

            override fun onPeerDisconnected() {
                // Transition to disconnected state
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                gattServer.stop()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {}
            override fun onMessageReceived(data: ByteArray) {
                Log.d(
                    "TransportBlePeripheralServerReader.gattCallback.messageReceived",
                    data.toString()
                )

                try {
                    gattServer.sendTransportSpecificTermination()
                    gattServer.stop()

                    callback?.update(mapOf(Pair("mdl", data)))

                    // Transition to disconnected state after successful message handling
                    stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                } catch (error: Exception) {
                    Log.e("TransportBlePeripheralServerReader.gattCallback.onMessageReceived", error.toString())
                    stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
                }
            }

            override fun onTransportSpecificSessionTermination() {
                Log.d("TransportBlePeripheralServerReader.gattCallback.termination", "Terminated")
            }

            override fun onError(error: Throwable) {
                Log.d("TransportBlePeripheralServerReader.gattCallback.onError", error.toString())
                // Transition to error state
                stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            }

            override fun onLog(message: String) {
                Log.d("TransportBlePeripheralServerReader.gattCallback.onLog", message)
            }

            override fun onState(state: String) {
                callback?.update(mapOf(Pair("state", state)))
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
            Log.e("TransportBlePeripheralServerReader.start", error.toString())
        }

        gattServer = GattServer(
            gattServerCallback,
            serviceUUID,
            true
        )

        blePeripheral = BlePeripheral(blePeripheralCallback, serviceUUID, bluetoothAdapter)
        try {
            blePeripheral.advertise()
            gattServer.start(identValue)
        } catch (error: Exception) {
            Log.e("TransportBlePeripheralServerReader.start", error.toString())
            stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            throw error
        }
    }

    fun stop() {
        // Transition to disconnecting state
        if (stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTING)) {
            if (this::previousAdapterName.isInitialized) {
                try {
                    bluetoothAdapter.name = previousAdapterName
                } catch (error: SecurityException) {
                    Log.e("TransportBlePeripheralServerReader.stop", error.toString())
                }
            }

            blePeripheral.stopAdvertise()
            gattServer.stop()

            // Transition to disconnected state
            stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
        } else {
            Log.w("TransportBlePeripheralServerReader.stop", "Failed to transition to DISCONNECTING state")
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
