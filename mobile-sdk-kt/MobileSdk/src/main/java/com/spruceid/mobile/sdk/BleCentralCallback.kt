package com.spruceid.mobile.sdk

import android.bluetooth.le.ScanResult

/**
 * Type definition for the BLE Central callback.
 */
abstract class BleCentralCallback {
    open fun onScanResult(callbackType: Int, result: ScanResult) {}
    open fun onError(error: Throwable) {}
    open fun onLog (message: String) {}
    open fun onState (state: String) {}

    open fun onMessageReceived(data: ByteArray) {}
}