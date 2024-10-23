package com.spruceid.mobile.sdk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import java.util.*


abstract class BlePeripheralCallback {
    open fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
    open fun onStartFailure(errorCode: Int) {}
    open fun onError(error: Throwable) {}
    open fun onLog(message: String) {}
    open fun onState (state: String) {}
}

class BlePeripheral(private var callback: BlePeripheralCallback,
                    private var serviceUUID: UUID,
                    bluetoothAdapter: BluetoothAdapter) {

    private var bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

    /**
     * Advertisement callback.
     */
    private val leAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            callback.onState(BleStates.AdvertisementStarted.string)
            callback.onLog("Advertisement has started with $serviceUUID service id.")
        }
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                callback.onError(Error("Advertise Failed Already Started."))
            }

            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                callback.onError(Error("Advertise Failed Data Too Large."))
            }

            if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                callback.onError(Error("Advertise Failed Feature Unsupported."))
            }

            if (errorCode == ADVERTISE_FAILED_INTERNAL_ERROR) {
                callback.onError(Error("Advertise Failed Internal Error."))
            }

            if (errorCode ==ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                callback.onError(Error("Advertise Failed Too Many Advertisers."))
            }

            callback.onState(BleStates.AdvertisementFailed.string)
            callback.onError(Error("Failed to start advertising."))
        }
    }

    /**
     * Starts to advertise the device/peripheral for connection.
     */
    fun advertise() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false) // Fails: Too large when on
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        try {
            bluetoothLeAdvertiser.startAdvertising(settings, data, leAdvertiseCallback)
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    /**
     * Stops advertising the device/peripheral.
     */
    fun stopAdvertise() {
        try {
            bluetoothLeAdvertiser.stopAdvertising(leAdvertiseCallback)

            callback.onState(BleStates.StopAdvertise.string)
            callback.onLog("Stopping Peripheral advertise.")
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }
}