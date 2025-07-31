package com.spruceid.mobile.sdk

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.*

/**
 * Determines the transport type ex: BLE, Wifi Aware, NFC, etc.
 * 18013-5 section 6.3.2.5 Table 2.
 */
class Transport(private var bluetoothManager: BluetoothManager) {

    public lateinit var transportBLE: TransportBle

//    this.bleManager!!.initialize(
//                "Holder",
//                this.uuid,
//                "BLE",
//                "Central",
//                session!!.getBleIdent(),
//                ::updateRequestData,
//                context,
//                callback
//            )

    /*
    * Initialize BLE Transport for the mDL Holder
    *
    *
    * */
    fun initializeHolder(
        context: Context,
        uuid: UUID,
        bleIdent: ByteArray,
        updateRequestData: ((data: ByteArray) -> Unit)? = null,
        callback: BLESessionStateDelegate?,
    ) {
        initialize(
            "Holder",
            uuid,
            "BLE",
            "Central",
            bleIdent,
            updateRequestData,
            context,
            callback
        )
    }

    /**
     * Selects and initializes the transport method.
     */
    fun initialize(
        application: String,
        serviceUUID: UUID,
        deviceRetrieval: String,
        deviceRetrievalOption: String,
        ident: ByteArray,
        updateRequestData: ((data: ByteArray) -> Unit)? = null,
        context: Context,
        callback: BLESessionStateDelegate?,
        encodedEDeviceKeyBytes: ByteArray = ByteArray(0)
    ) {

        /**
         * Device Retrieval
         *
         * BLE
         * NFC
         * Wi-Fi Aware
         */
        if (deviceRetrieval == "BLE") {
            Log.d("Transport.initialize", "-- Selecting BLE Retrieval --")

            transportBLE = TransportBle(bluetoothManager)
            transportBLE.initialize(
                application,
                serviceUUID,
                deviceRetrievalOption,
                ident,
                updateRequestData,
                context,
                callback,
                encodedEDeviceKeyBytes
            )
        }

        /**
         * Server Retrieval
         *
         * WebAPI
         * OIDC
         */
    }

    fun setUpdateRequestDataCallback(callback: ((data: ByteArray) -> Unit)) {

        transportBLE.setUpdateRequestDataCallback(callback)
    }

    /**
     * For sending the mDL based on initialized transport.
     */
    fun send(payload: ByteArray) {
        if (this::transportBLE.isInitialized) {
            transportBLE.send(payload)
        }
    }

    /**
     * Stops emitting or disconnects depending on the transport method initialized.
     */
    fun terminate() {
        if (this::transportBLE.isInitialized) {
            transportBLE.terminate()
        }
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        if (this::transportBLE.isInitialized) {
            transportBLE.terminate()
        }
    }
}