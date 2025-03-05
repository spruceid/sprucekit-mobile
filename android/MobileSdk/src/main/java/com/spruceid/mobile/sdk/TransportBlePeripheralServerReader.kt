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

package com.spruceid.mobile.sdk

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log
import java.util.*

/**
 * The responsibility of this class is to advertise data and be available for connection. AKA Reader.
 * 18013-5 section 8.3.3.1.1.4 Table 11.
 */
class TransportBlePeripheralServerReader(
    private val callback: BLESessionStateDelegate?,
    private var application: String,
    private var bluetoothManager: BluetoothManager,
    private var serviceUUID: UUID,
    private val context: Context
) {
    private var bluetoothAdapter: BluetoothAdapter? = null

    private lateinit var previousAdapterName: String
    private lateinit var blePeripheral: BlePeripheral
    private lateinit var gattServer: GattServer
    private lateinit var identValue: ByteArray

    private var characteristicStateUuid: UUID =
        UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
    private var characteristicClient2ServerUuid: UUID =
        UUID.fromString("00000006-a123-48ce-896b-4c76973373e6")
    private var characteristicServer2ClientUuid: UUID =
        UUID.fromString("00000007-a123-48ce-896b-4c76973373e6")
    private var characteristicIdentUuid: UUID =
        UUID.fromString("00000008-a123-48ce-896b-4c76973373e6")
    private var characteristicL2CAPUuid: UUID =
        UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6")

    private var logIndex: Int = 0

    /**
     * Sets up peripheral with GATT server mode.
     */
    fun start(ident: ByteArray, encodedEDeviceKeyBytes: ByteArray) {

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

                blePeripheral.stopAdvertise()
                gattServer.sendMessage(encodedEDeviceKeyBytes)

                Log.d(
                    "TransportBlePeripheralServerReader.gattCallback.onPeerConnected",
                    "Peer connected"
                )
            }

            override fun onPeerDisconnected() {
                gattServer.stop()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {}
            override fun onMessageReceived(data: ByteArray) {

                Log.d(
                    "TransportBlePeripheralServerReader.gattCallback.messageReceived",
                    data.toString()
                )
                gattServer.sendTransportSpecificTermination()
                gattServer.stop()

                callback?.update(mapOf(Pair("mdl", data)))
            }

            override fun onTransportSpecificSessionTermination() {
                Log.d("TransportBlePeripheralServerReader.gattCallback.termination", "Terminated")
            }

            override fun onError(error: Throwable) {
                Log.d("TransportBlePeripheralServerReader.gattCallback.onError", error.toString())
            }

            override fun onLog(message: String) {
                Log.d("TransportBlePeripheralServerReader.gattCallback.onLog", message)
            }

            override fun onState(state: String) {
                callback?.update(mapOf(Pair("state", state)))
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
            println(error)
        }

        if (bluetoothAdapter == null) {
            println("No Bluetooth Adapter")
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
            characteristicIdentUuid,
            characteristicL2CAPUuid
        )

        blePeripheral = BlePeripheral(blePeripheralCallback, serviceUUID, bluetoothAdapter!!)
        blePeripheral.advertise()
        gattServer.start(identValue)
    }

    fun stop() {
        if (this::previousAdapterName.isInitialized) {
            try {
                bluetoothAdapter!!.name = previousAdapterName
            } catch (error: SecurityException) {
                println(error)
            }
        }

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
