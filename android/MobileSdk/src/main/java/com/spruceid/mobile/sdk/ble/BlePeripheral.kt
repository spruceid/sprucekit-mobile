package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.*


abstract class BlePeripheralCallback {
    open fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
    /**
     * Called when `startAdvertising` fails with one of the
     * `AdvertiseCallback.ADVERTISE_FAILED_*` codes. The SDK only delivers
     * the *final* failure — transient `ADVERTISE_FAILED_INTERNAL_ERROR`s
     * are retried internally with a short backoff (see [BlePeripheral]).
     */
    open fun onStartFailure(errorCode: Int) {}
    open fun onError(error: Throwable) {}
    open fun onLog(message: String) {}
    open fun onState(state: String) {}
}

class BlePeripheral(
    private var callback: BlePeripheralCallback,
    private var serviceUUID: UUID,
    private val stateMachineType: BleConnectionStateMachineInstanceType = BleConnectionStateMachineInstanceType.SERVER
) {

    private val bluetoothAdapter: BluetoothAdapter = BleConnectionStateMachine.getInstance(stateMachineType).getBluetoothManager().adapter
    private val bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

    /**
     * Retry state for transient `ADVERTISE_FAILED_INTERNAL_ERROR` failures.
     * `TOO_MANY_ADVERTISERS` is *not* retried automatically — it indicates
     * the system advertiser slots are saturated (by this app or another),
     * and the right recovery is for callers to release pressure (e.g. stop
     * advertising once a peer has connected) rather than spinning. Other
     * permanent codes (DATA_TOO_LARGE, FEATURE_UNSUPPORTED, ALREADY_STARTED)
     * also fail fast.
     */
    private val retryHandler = Handler(Looper.getMainLooper())
    private var transientRetryAttempt = 0

    /**
     * Advertisement callback. Each `startAdvertising` failure now delivers
     * exactly one terminal error to [BlePeripheralCallback]; the previous
     * implementation fired both a per-code error *and* an unconditional
     * generic "Failed to start advertising." error, causing subscribers to
     * double-handle a single failure.
     */
    private val leAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            transientRetryAttempt = 0
            callback.onState(BleStates.AdvertisementStarted.string)
            callback.onLog("Advertisement has started with $serviceUUID service id.")
        }

        override fun onStartFailure(errorCode: Int) {
            // Retry `INTERNAL_ERROR` with exponential backoff — empirically
            // it's the most-common transient (radio state churn, BT stack
            // hiccup). All other codes are reported and left to the caller.
            if (errorCode == ADVERTISE_FAILED_INTERNAL_ERROR &&
                transientRetryAttempt < MAX_TRANSIENT_RETRIES) {
                transientRetryAttempt++
                val delay = RETRY_BASE_DELAY_MS shl (transientRetryAttempt - 1)
                callback.onLog(
                    "Advertise failed with INTERNAL_ERROR; retrying in " +
                        "${delay}ms (attempt $transientRetryAttempt/$MAX_TRANSIENT_RETRIES)."
                )
                retryHandler.postDelayed({ startAdvertisingInternal() }, delay)
                return
            }

            val message = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Advertise Failed Already Started."
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertise Failed Data Too Large."
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertise Failed Feature Unsupported."
                ADVERTISE_FAILED_INTERNAL_ERROR ->
                    "Advertise Failed Internal Error (after $transientRetryAttempt retries)."
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "Advertise Failed Too Many Advertisers (system advertiser slots " +
                        "saturated — ensure previous sessions stopped advertising)."
                else -> "Failed to start advertising (code=$errorCode)."
            }
            callback.onState(BleStates.AdvertisementFailed.string)
            callback.onStartFailure(errorCode)
            callback.onError(Error(message))
        }
    }

    /**
     * Starts to advertise the device/peripheral for connection. Resets any
     * lingering retry state so a fresh call after [stopAdvertise] starts
     * with a full retry budget.
     */
    fun advertise() {
        transientRetryAttempt = 0
        startAdvertisingInternal()
    }

    private fun startAdvertisingInternal() {
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
     * Stops advertising the device/peripheral. Also cancels any pending
     * transient-failure retry scheduled by [leAdvertiseCallback.onStartFailure]
     * so a caller-initiated stop is authoritative.
     */
    fun stopAdvertise() {
        retryHandler.removeCallbacksAndMessages(null)
        transientRetryAttempt = 0
        try {
            bluetoothLeAdvertiser.stopAdvertising(leAdvertiseCallback)
            callback.onState(BleStates.StopAdvertise.string)
            callback.onLog("Stopping Peripheral advertise.")
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    companion object {
        private const val MAX_TRANSIENT_RETRIES = 2
        private const val RETRY_BASE_DELAY_MS = 500L
    }
}
