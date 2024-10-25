package com.spruceid.mobile.sdk

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log
import androidx.annotation.NonNull
import java.util.*

/**
 * The responsibility of this class is to advertise data and be available for connection. AKA Holder.
 * 18013-5 section 8.3.3.1.1.4 Table 12.
 */
class TransportBlePeripheralServerHolder(
    private var application: String,
    private var bluetoothManager: BluetoothManager,
    private var serviceUUID: UUID,
    private var context: Context
) {

    private var bluetoothAdapter: BluetoothAdapter? = null

    private lateinit var previousAdapterName: String
    private lateinit var blePeripheral: BlePeripheral
    private lateinit var gattServer: GattServer

    private var characteristicStateUuid: UUID =
        UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
    private var characteristicClient2ServerUuid: UUID =
        UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
    private var characteristicServer2ClientUuid: UUID =
        UUID.fromString("00000003-a123-48ce-896b-4c76973373e6")
    private var characteristicL2CAPUuid: UUID =
        UUID.fromString("0000000a-a123-48ce-896b-4c76973373e6")

    /**
     * Sets up peripheral with GATT server mode.
     */
    fun start() {

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
            }

            override fun onPeerDisconnected() {
                Log.d(
                    "TransportBlePeripheralServerHolder.gattServerCallback.onPeerDisconnected",
                    "Peer Disconnected"
                )
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
                gattServer.stop()
            }

            override fun onLog(message: String) {
                Log.d("TransportBlePeripheralServerHolder.gattServerCallback.onLog", message)
            }

            override fun onState(state: String) {
                Log.d("TransportBlePeripheralServerHolder.gattServerCallback.onState", state)
            }
        }

        bluetoothAdapter = bluetoothManager.adapter

        /**
         * Setting up device name for easier identification after connection - too large to be in
         * advertisement data.
         */
        try {
            previousAdapterName = bluetoothAdapter!!.name
            bluetoothAdapter!!.name = "mDL $application Device"
        } catch (error: SecurityException) {
            Log.e("TransportBlePeripheralServerHolder.start", error.toString())
        }

        if (bluetoothAdapter == null) {
            Log.e("TransportBlePeripheralServerHolder.start", "No Bluetooth Adapter")
            return
        }

        gattServer = GattServer(
            gattServerCallback,
            context,
            bluetoothManager,
            serviceUUID,
            characteristicStateUuid,
            characteristicClient2ServerUuid,
            characteristicServer2ClientUuid,
            null,
            characteristicL2CAPUuid
        )

        blePeripheral = BlePeripheral(blePeripheralCallback, serviceUUID, bluetoothAdapter!!)
        blePeripheral.advertise()
        gattServer.start(null)
    }

    /**
     * For sending the mDL.
     */
    fun send(payload: ByteArray) {
        gattServer.sendMessage(payload)
    }

    fun stop() {
        if (this::previousAdapterName.isInitialized) {
            try {
                bluetoothAdapter!!.name = previousAdapterName
            } catch (error: SecurityException) {
                Log.e("TransportBlePeripheralServerHolder.stop", error.toString())
            }
        }

        gattServer.sendTransportSpecificTermination()
        blePeripheral.stopAdvertise()
        gattServer.stop()
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        blePeripheral.stopAdvertise()
        gattServer.stop()
        gattServer.reset()
    }
}