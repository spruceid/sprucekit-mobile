package com.spruceid.sprucekit_mobile

import android.content.ComponentName
import android.content.Context
import com.spruceid.mobile.sdk.nfc.BaseNfcPresentationService
import com.spruceid.mobile.sdk.nfc.NfcPresentationError
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo

/**
 * Host card emulation service for ISO 18013-5 NFC device engagement.
 *
 * The plugin manifest declares this service with the mdoc AID, so every app
 * that depends on the plugin gets it through manifest merge. The SDK base
 * class runs the APDU exchange and the static handover. This subclass only
 * gates the exchange and forwards the outcome to [MdlPresentationAdapter]
 * through [listener].
 *
 * The system creates the service instance. The adapter lives in the Flutter
 * engine. The companion object connects the two.
 */
class NfcPresentationService : BaseNfcPresentationService() {

    internal interface Listener {
        /**
         * True while the service must answer APDUs: from the arm until the
         * wallet cancels or starts another session. This includes the BLE
         * phase, because readers keep reading after the handover.
         */
        fun isListening(): Boolean

        fun onCarrierInfo(carrierInfo: NegotiatedCarrierInfo)

        fun onNegotiationFailed(error: NfcPresentationError)
    }

    override fun shouldPerformHandoverEngagement(): Boolean {
        val current = listener ?: return false
        return current.isListening() && appInForeground()
    }

    override fun negotiationFailed(error: NfcPresentationError) {
        listener?.onNegotiationFailed(error)
    }

    override fun negotiatedTransport(carrierInfo: NegotiatedCarrierInfo) {
        listener?.onCarrierInfo(carrierInfo)
    }

    override fun componentName(): ComponentName = componentName(applicationContext)

    companion object {
        @Volatile
        internal var listener: Listener? = null

        fun componentName(context: Context): ComponentName =
            ComponentName(context, NfcPresentationService::class.java)
    }
}
