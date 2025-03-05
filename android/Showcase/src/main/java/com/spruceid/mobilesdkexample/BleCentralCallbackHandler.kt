package com.spruceid.mobilesdkexample

import android.bluetooth.le.ScanResult
import android.util.Log
import com.spruceid.mobile.sdk.BleCentralCallback

class BleCentralCallbackHandler : BleCentralCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        Log.d("BleCentralCallbackHandler.onScanResult", "$callbackType")
    }

    override fun onError(error: Throwable) {
        Log.d("BleCentralCallbackHandler.onError", "$error")
    }

    override fun onLog(message: String) {
        Log.d("BleCentralCallbackHandler.onLog", message)
    }

    override fun onState(state: String) {
        Log.d("BleCentralCallbackHandler.onState", state)
    }
}