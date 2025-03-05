package com.spruceid.mobile.sdk

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.util.*

/**
 * The role of central is to scan for a peripheral and connect. AKA holder.
 * 18013-5 section 8.3.3.1.1.4 Table 11.
 */
class TransportBleCentralClientHolder(
    private var application: String,
    private var bluetoothManager: BluetoothManager,
    private var serviceUUID: UUID,
    private var updateRequestData: (data: ByteArray) -> Unit,
    private var context: Context,
    private var callback: BLESessionStateDelegate?,
) {
    private var bluetoothAdapter: BluetoothAdapter? = null

    private lateinit var previousAdapterName: String
    private lateinit var bleCentral: BleCentral
    private lateinit var gattClient: GattClient
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


    /**
     * Sets up central with GATT client mode.
     */
    fun connect(ident: ByteArray) {
        /**
         * Should be generated based on the 18013-5 section 8.3.3.1.1.3.
         */
        identValue = ident

        /**
         * BLE Central callback.
         */
        val bleCentralCallback: BleCentralCallback = object : BleCentralCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                /**
                 * Once we found a device we don't have to scan anymore.
                 */
                bleCentral.stopScan()
                gattClient.connect(result.device, identValue)
            }

            override fun onLog(message: String) {
                Log.d("TransportBleCentralClientHolder.bleCentralCallback.onLog", message)
            }

            override fun onState(state: String) {
                Log.d("TransportBleCentralClientHolder.bleCentralCallback.onState", state)
            }
        }

        /**
         * GATT client callback.
         */
        val gattClientCallback: GattClientCallback = object : GattClientCallback() {
            override fun onPeerConnected() {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onPeerConnected",
                    "Peer Connected"
                )
                callback?.update(mapOf(Pair("connected", "")))
            }

            override fun onPeerDisconnected() {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onPeerDisconnected",
                    "Peer Disconnected"
                )
                callback?.update(mapOf(Pair("disconnected", "")))
                gattClient.disconnect()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onMessageSendProgress",
                    "progress: $progress max: $max"
                )

                if (progress == max) {
                    callback?.update(mapOf(Pair("success", "")))
                } else {
                    callback?.update(
                        mapOf(
                            Pair(
                                "uploadProgress",
                                mapOf(Pair("curr", progress), Pair("max", max))
                            )
                        )
                    )
                }
            }

            override fun onMessageReceived(data: ByteArray) {
                super.onMessageReceived(data)
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onMessageReceived",
                    "Message received ${byteArrayToHex(data)}"
                )

                try {
                    updateRequestData(data)
                } catch (e: Error) {
                    Log.e("MDoc", e.toString())
                    callback?.update(mapOf(Pair("error", e)))
                }
            }

            override fun onTransportSpecificSessionTermination() {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onTransportSpecificSessionTermination",
                    "Transort Specific Session Terminated"
                )

                gattClient.disconnect()
            }

            override fun onLog(message: String) {
                Log.d("TransportBleCentralClientHolder.gattClientCallback.onLog", message)
            }

            override fun onState(state: String) {
                Log.d("TransportBleCentralClientHolder.gattClientCallback.onState", state)
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
            Log.e("TransportBleCentralClientHolder.connect", error.toString())
        }

        if (bluetoothAdapter == null) {
            Log.e("TransportBleCentralClientHolder.connect", "No Bluetooth Adapter")
            return
        }

        gattClient = GattClient(
            gattClientCallback,
            context,
            bluetoothAdapter,
            serviceUUID,
            characteristicStateUuid,
            characteristicClient2ServerUuid,
            characteristicServer2ClientUuid,
            characteristicIdentUuid,
            characteristicL2CAPUuid
        )

        bleCentral = BleCentral(bleCentralCallback, serviceUUID, bluetoothAdapter!!)
        bleCentral.scan()
    }

    /**
     * For sending the mDL.
     */
    fun send(payload: ByteArray) {
        gattClient.sendMessage(payload)
    }

    fun disconnect() {
        if (this::previousAdapterName.isInitialized) {
            try {
                bluetoothAdapter!!.name = previousAdapterName
            } catch (error: SecurityException) {
                Log.e("TransportBleCentralClientHolder.disconnect", error.toString())
            }
        }

        gattClient.sendTransportSpecificTermination()
        bleCentral.stopScan()
        gattClient.disconnect()
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        bleCentral.stopScan()
        gattClient.disconnect()
        gattClient.reset()
    }
}
