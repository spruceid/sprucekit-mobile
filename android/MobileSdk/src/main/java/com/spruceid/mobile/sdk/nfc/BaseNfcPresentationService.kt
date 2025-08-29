package com.spruceid.mobile.sdk.nfc

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spruceid.mobile.sdk.rs.ApduHandoverDriver
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class NfcPresentationError(val humanReadable: String) {
    NEGOTIATION_FAILED("This wallet is not compatible with the current reader."),
    CONNECTION_CLOSED("The device was removed from the reader too quickly."),
}

abstract class BaseNfcPresentationService : HostApduService() {

    private val TAG = "BaseNfcPresentationService"

    private var _apduHandoverDriver: ApduHandoverDriver? = null

    private val apduHandoverDriver: ApduHandoverDriver
        get() {
            if (_apduHandoverDriver == null) {
                _apduHandoverDriver = ApduHandoverDriver()
            }
            return _apduHandoverDriver!!
        }

    private var currentInteractionId: Long = 0
    private var inNegotiation = false

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {

        currentInteractionId++

        if (!inNegotiation) {
            negotiationStarted()
            inNegotiation = true
        }

        NfcListenManager.setRequestedFromNfcCommands(true, applicationContext, componentName())

        @OptIn(ExperimentalStdlibApi::class)
        run { Log.d(TAG, "recv: ${commandApdu.toHexString()}") }
        val ret = apduHandoverDriver.processApdu(commandApdu)
        @OptIn(ExperimentalStdlibApi::class) run { Log.d(TAG, "send: ${ret.toHexString()}") }
        val carrierInfo = apduHandoverDriver.getCarrierInfo()
        if (carrierInfo != null) {
            Log.d(TAG, "Carrier info available: $carrierInfo")
            Handler(Looper.getMainLooper()).post { negotiatedTransport(carrierInfo) }
        }
        return ret
    }

    override fun onDeactivated(reason: Int) {

        fun defer(delay: Duration, action: Runnable) {
            Handler(Looper.getMainLooper()).postDelayed(action, delay.inWholeMilliseconds)
        }

        apduHandoverDriver.reset()

        // Wait a moment before turning off NDEF listening.
        // This is because the shift from MDOC -> NDEF triggers a disconnect, but
        // this disconnect is expected and not an error.
        val prevInteractionId = currentInteractionId
        defer(1.seconds) {
            if (prevInteractionId == currentInteractionId) {
                NfcListenManager.setRequestedFromNfcCommands(
                        false,
                        applicationContext,
                        componentName()
                )
                inNegotiation = false
            }
        }

        Log.d(TAG, "deactivated: $reason")
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

    abstract fun componentName(): ComponentName

    /**
     * This method is called at the beginning of the NFC negotiation process. If you want to do any
     * background processing/loading, you can kick it off here and wait for it to complete in
     * `negotiatedTransport`. NOTE: This happens at the beginning of the NFC negotiation process -
     * there is no guarantee that the app is in the foreground, and no guarantee that NFC
     * negotiation will succeed!
     */
    protected fun negotiationStarted() {}

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
    abstract fun negotiatedTransport(carrierInfo: NegotiatedCarrierInfo)
}
