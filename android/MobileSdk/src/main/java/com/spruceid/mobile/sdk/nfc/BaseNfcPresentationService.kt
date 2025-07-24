package com.spruceid.mobile.sdk.nfc

import android.app.ActivityManager
import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

enum class NfcPresentationError(val humanReadable: String) {
    NEGOTIATION_FAILED("This wallet is not compatible with the current reader."),
    CONNECTION_CLOSED("The device was removed from the reader too quickly."),
}

abstract class BaseNfcPresentationService : HostApduService() {

    var inNegotiation = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {

        val hexBytes = "[${commandApdu?.joinToString(", ") { "%02X".format(it) }}]"

        // Log bytes we received
        Log.d("NFC", "Received command APDU: $hexBytes")

        if (!inNegotiation) {
            // TODO: Validate that this is the right command APDU
            // SELECT APPLICATION MDOC
            inNegotiation = true
            Log.d("NFC", "Returning OK")
            return byteArrayOf(0x90.toByte(), 0x00.toByte())
        }

        // TODO("Not yet implemented")
        return null
    }

    override fun onDeactivated(reason: Int) {

        inNegotiation = false

        Log.d("NFC", "deactivated: $reason")

        // TODO("Not yet implemented")
    }

    fun appInForeground(): Boolean {
        val activityManager =
                this.baseContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false
        return runningProcesses.any {
            it.processName == baseContext.packageName &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /**
     * This method is called when NFC negotiation failed. This could be due to failing to find a
     * compatible transport method.
     */
    abstract fun negotiationFailed(error: NfcPresentationError)

    /**
     * This method is called when an NFC reader has successfully negotiated transport for a
     * credential presentation. This *may* be called while the app is in the background, so no
     * assumptions can be made about the app's UI state.
     */
    abstract fun negotiatedTransport()
}
