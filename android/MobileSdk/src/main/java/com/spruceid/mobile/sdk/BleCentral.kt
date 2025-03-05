package com.spruceid.mobile.sdk

import android.bluetooth.le.*
import android.os.Handler
import android.os.ParcelUuid
import java.util.*

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Context.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.util.Log

class BleCentral(
    private var callback: BleCentralCallback,
    private var serviceUUID: UUID,
    bluetoothAdapter: BluetoothAdapter
) {

    private val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    private var scanning = false
    private val handler = Handler(Looper.myLooper()!!)

    // Limits scanning to 3 min - preserves battery life - ideally should be lower.
    private val scanPeriod: Long = 180000

    /**
     * Scan callback.
     */
    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            callback.onState(BleStates.DeviceDiscovered.string)
            callback.onLog("Device ${result.device.address} discovered.")
            callback.onScanResult(callbackType, result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)

            callback.onError(Error("Should not be using batch, $results."))
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)

            callback.onError(Error("Fail to scan $errorCode."))
        }
    }

    /**
     * Starts to scan for devices/peripherals to connect to - looks for a specific service UUID.
     *
     * Scanning is limited with a timeout to preserve battery life of a device.
     */
    fun scan() {
        val filter: ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()

        val filterList: MutableList<ScanFilter> = ArrayList()
        filterList.add(filter)

        val settings: ScanSettings = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            if (!scanning) {
                handler.postDelayed({
                    scanning = false
                    bluetoothLeScanner.stopScan(leScanCallback)

                    callback.onState(BleStates.StopScan.string)
                    callback.onLog("Stopping Central scan.")
                }, scanPeriod)
                scanning = true
                bluetoothLeScanner.startScan(filterList, settings, leScanCallback)

                callback.onState(BleStates.Scanning.string)
                callback.onLog("Starting Central scan.")
            } else {
                scanning = false
                bluetoothLeScanner.stopScan(leScanCallback)

                callback.onState(BleStates.StopScan.string)
                callback.onLog("Stopping Central scan.")
            }
        } catch (error: SecurityException) {
            callback.onError(error)
        } catch (error: IllegalStateException) {
            // Quoth the docs:
            //    Thrown by cancellable suspending functions if the coroutine is cancelled while it is
            // suspended.  It indicates normal cancellation of a coroutine.
            callback.onLog("Ending Central scan (coroutine cancelled).")
        }
    }

    /**
     * Stops scanning for devices/peripherals.
     */
    fun stopScan() {
        try {
            bluetoothLeScanner.stopScan(leScanCallback)
            scanning = false

            callback.onState(BleStates.StopScan.string)
            callback.onLog("Stopping Central scan.")
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }
}

fun getPermissions(): List<String> {
    val permissions =
        arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * The OS seems to omit certain permission requests like "BLUETOOTH" to the user depending
     * on the OS version. Although, this does not cause an error it will create a dependency on
     * a permission that can never be accepted.
     */
    if (Build.VERSION.SDK_INT >= 31) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        permissions.add(Manifest.permission.BLUETOOTH)
        permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        permissions.add(Manifest.permission.BLUETOOTH_PRIVILEGED)
    }

    return permissions
}

fun isBluetoothEnabled(context: Context): Boolean {
    val bluetoothManager = context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    return if (bluetoothAdapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
        false
    } else {
        bluetoothAdapter.isEnabled
    }
}

fun getBluetoothManager(context: Context): BluetoothManager? {
    return context.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
}
