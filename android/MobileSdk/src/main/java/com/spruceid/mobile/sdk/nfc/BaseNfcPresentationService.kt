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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class NfcPresentationError(val humanReadable: String) {
    NEGOTIATION_FAILED("This wallet is not compatible with the current reader."),
    CONNECTION_CLOSED("The device was removed from the reader too quickly."),
}

abstract class BaseNfcPresentationService : HostApduService() {

    // The usage of postDelayed was causing instances of the service to seemingly get copied?
    // Because of this, we store all of the NFC state in static variables, so that we don't end up
    // with subtly stale data.
    // There should logically only ever be one instance of an NfcPresentationService anyway, so this
    // shouldn't cause any issues.
    companion object {
        private var currentInteractionId: Long = 0
        private var resetQueued = false
        private var doNotNotifyOnDisconnect = false
        private var negotiationFailedFlag = false
        private var inNegotiation = false
        private var _apduHandoverDriver: ApduHandoverDriver? = null
        private val apduHandoverDriver: ApduHandoverDriver
            get() {
                if (_apduHandoverDriver == null) {
                    _apduHandoverDriver = ApduHandoverDriver(false) // Use static handover, temporarily
                }
                return _apduHandoverDriver!!
            }
    }

    private fun defer(delay: Duration, action: Runnable) {
        Handler(Looper.getMainLooper()).postDelayed(action, delay.inWholeMilliseconds)
    }

    private val TAG = "BaseNfcPresentationService"

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {

        if(resetQueued) {
            Log.w(TAG, "Resetting APDU driver")
            resetQueued = false
            apduHandoverDriver.reset()
        }

        val isReadBinaryCommand = commandApdu.size > 4 && commandApdu[1] == 0xB0.toByte()

        currentInteractionId++

        // Read binary commands shouldn't affect state flags,
        // since they may come after the success state is reached.
        if(!isReadBinaryCommand) {
            if (!inNegotiation) {
                negotiationStarted()
                inNegotiation = true
            }
            Log.d(TAG, "Got !read cmd, resetting flags")
            doNotNotifyOnDisconnect = false
            negotiationFailedFlag = false
        }

        NfcListenManager.setExpectedNdefFromHandover(true, applicationContext, componentName())

        val ret = apduHandoverDriver.processApdu(commandApdu)
        val carrierInfo = apduHandoverDriver.getCarrierInfo()
        if (carrierInfo != null) {
            Log.d(TAG, "Negotiated! Setting flags.")
            Handler(Looper.getMainLooper()).post { negotiatedTransport(carrierInfo) }
            doNotNotifyOnDisconnect = true
        }
        val returnedApduSuccessStatus =
            ret.size >= 2 &&
                    ret[ret.size - 2] == (0x90.toByte()) &&
                    ret[ret.size - 1] == (0x00.toByte())

        // If negotiation failed, send the negotiation failed message to the user
        // and flag that an error message has already been sent.
        // The error flag prevents a double error notification upon NFC disconnect.
        // We defer the actual "Negotiation Failed" message because some readers try multiple
        // handover techniques before giving up.
        if (!returnedApduSuccessStatus) {
            doNotNotifyOnDisconnect = true
            negotiationFailedFlag = true
            Log.e(TAG, "ERR response, setting flags")
        }

        return ret
    }

    override fun onDeactivated(reason: Int) {
        currentInteractionId++;

        // Wait a moment before turning off NDEF listening.
        // This is because the shift from mDoc -> NDEF triggers a disconnect, but
        // this disconnect is expected and not an error.
        val prevInteractionId = currentInteractionId

        // NOTE: See the comment above the companion object definition for safety considerations
        //       when working within these deferred blocks.
        //       Essentially, in some situations, values get copied. Test changes
        //       to ensure this is not happening.

        // The pattern here is: run each block after N time of NFC inactivity.
        // This does not correspond to the spec, necessarily, but has proven necessary due to
        // the individual quirks of various readers.

        // The only part of this function that *may* be required by the spec is
        // the first block, but it only seems to be required (and therefore, well-defined) when
        // using TNEP, which is not used in static handover - the method currently in use.
        //  • Also, if we *did* use TNEP, this would not necessarily comply with its requirements.
        //    See: TNEP 1.0 §4.1.2

        // After 250ms of no NFC data transfer, if we have the negotiation failed flag,
        // notify the user that negotiation failed.
        // We wait before showing this because sometimes readers will blast the wallet
        // with multiple handover requests in different formats to see which one the wallet
        // responds to.
        defer(250.milliseconds) {
            if (prevInteractionId == currentInteractionId) {
                resetQueued = true
                if (negotiationFailedFlag) {
                    negotiationFailed(NfcPresentationError.NEGOTIATION_FAILED)
                    negotiationFailedFlag = false
                    doNotNotifyOnDisconnect = true
                }
            }
        }

        // After one second of no NFC data transfer:
        //  1. flag to the NfcListenManager that we're no longer in an NFC negotiation
        //  2. notify the user that communication failed
        defer(1.seconds) {
            if (prevInteractionId == currentInteractionId) {
                // We bind systemwide to the APDU AID for mDoc, and when we recv an mDoc msg,
                // we bind to the NDEF AID as well. If we haven't gotten an NFC message for a bit
                // of time, we should reset the OS's NFC listen state back to just mDoc.
                NfcListenManager.setExpectedNdefFromHandover(
                    false,
                    applicationContext,
                    componentName()
                )
                inNegotiation = false

                // If we've already sent an error message about what caused the disconnect, don't
                // send another error notif.
                // If we've successfully negotiated transport, we also shouldn't display an error.
                if (!doNotNotifyOnDisconnect) {
                    negotiationFailed(NfcPresentationError.CONNECTION_CLOSED)
                    doNotNotifyOnDisconnect = false
                }
            }
        }

        // After five seconds of no NFC data transfer, regenerate the BLE uuid and ephemeral keys.
        // We wait to do this because some readers have been found to eagerly request NFC handover
        // *even after successful handover*! We want subsequent requests from the same reader to
        // return and use the same UUID/keys, so we keep them around for a bit.
        defer(5.seconds) {
            if (prevInteractionId == currentInteractionId) {
                apduHandoverDriver.regenerateStaticBleKeys()
            }
        }

        Log.i(TAG, "deactivated: $reason")
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
