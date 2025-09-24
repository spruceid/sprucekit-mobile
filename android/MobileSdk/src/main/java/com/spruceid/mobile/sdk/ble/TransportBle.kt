package com.spruceid.mobile.sdk.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import androidx.annotation.RequiresPermission
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import java.util.*

/**
 * Selects the type of BLE transport option to use. 18013-5 section 8.3.3.1.1.
 */
class TransportBle {

    private val logger = BleLogger.getInstance("TransportBle")

    private lateinit var transportBleCentralClientHolder: TransportBleCentralClientHolder
    private lateinit var transportBlePeripheralServerHolder: TransportBlePeripheralServerHolder
    private lateinit var transportBlePeripheralServerReader: TransportBlePeripheralServerReader

    /**
     * Initializes one of the transport modes (Central Client/Peripheral Server).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun initialize(
        application: String,
        serviceUUID: UUID,
        deviceRetrievalOption: String,
        ident: ByteArray,
        updateRequestData: ((data: ByteArray) -> Unit)? = null,
        callback: BLESessionStateDelegate?,
        encodedEDeviceKeyBytes: ByteArray
    ) {

        /**
         * Transport Central Client Holder
         */
        if (deviceRetrievalOption == "Central" && application == "Holder") {
            logger.d("Selecting Transport Central Client Holder")
            if (updateRequestData != null) {
                transportBleCentralClientHolder = TransportBleCentralClientHolder(
                    application,
                    serviceUUID,
                    updateRequestData,
                    callback,
                )
                transportBleCentralClientHolder.connect(ident)
            }
        }

        /**
         * Transport Peripheral Server Holder
         */
        if (deviceRetrievalOption == "Peripheral" && application == "Holder") {
            logger.d("Selecting Peripheral Server Holder")

            transportBlePeripheralServerHolder = TransportBlePeripheralServerHolder(
                application, serviceUUID
            )
            transportBlePeripheralServerHolder.start()
        }

        /**
         * Transport Peripheral Server Reader
         */
        if (deviceRetrievalOption == "Peripheral" && application == "Reader") {
            logger.d("Selecting Peripheral Server Reader")

            transportBlePeripheralServerReader = TransportBlePeripheralServerReader(
                callback, application, serviceUUID
            )
            transportBlePeripheralServerReader.start(ident, encodedEDeviceKeyBytes)
        }
    }

    /**
     * For sending the mDL based on initialized transport option.
     */
    fun send(payload: ByteArray) {
        logger.logDataTransfer("Sending", payload.size)

        if (this::transportBleCentralClientHolder.isInitialized) {
            transportBleCentralClientHolder.send(payload)
        }

        if (this::transportBlePeripheralServerHolder.isInitialized) {
            transportBlePeripheralServerHolder.send(payload)
        }
    }

    /**
     * Terminates BLE transports based on what is initialized.
     */
    fun terminate() {
        logger.i("Terminating BLE transport")

        try {
            if (this::transportBleCentralClientHolder.isInitialized) {
                transportBleCentralClientHolder.disconnect()
            }

            if (this::transportBlePeripheralServerHolder.isInitialized) {
                transportBlePeripheralServerHolder.stop()
            }

            if (this::transportBlePeripheralServerReader.isInitialized) {
                transportBlePeripheralServerReader.stop()
            }
        } catch (e: Exception) {
            logger.e("Error during transport termination", e)
        }
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        logger.w("Performing hard reset of BLE transport")

        try {
            if (this::transportBleCentralClientHolder.isInitialized) {
                transportBleCentralClientHolder.hardReset()
            }

            if (this::transportBlePeripheralServerHolder.isInitialized) {
                transportBlePeripheralServerHolder.hardReset()
            }

            if (this::transportBlePeripheralServerReader.isInitialized) {
                transportBlePeripheralServerReader.hardReset()
            }
        } catch (e: Exception) {
            logger.e("Error during hard reset", e)
        }
    }
}