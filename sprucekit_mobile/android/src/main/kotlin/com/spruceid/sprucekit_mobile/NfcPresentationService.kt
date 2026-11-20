package com.spruceid.sprucekit_mobile

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import com.spruceid.mobile.sdk.nfc.BaseNfcPresentationService
import com.spruceid.mobile.sdk.nfc.NfcPresentationError
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo

/**
 * Host card emulation service for ISO 18013-5 NFC device engagement.
 *
 * The plugin ships the class and the AID resource. The app declares the
 * service in its own manifest, so only apps that opt in answer reader taps
 * (see README.md). The SDK base class runs the APDU exchange and the static
 * handover. This subclass only gates the exchange and forwards the outcome to
 * [MdlPresentationAdapter] through [listener].
 *
 * The system creates the service instance. The adapter lives in the Flutter
 * engine. The companion object connects the two.
 */
class NfcPresentationService : BaseNfcPresentationService() {

    internal interface Listener {
        /** True while the service must answer APDUs. See [NfcEngagementCoordinator.Phase]. */
        fun isListening(): Boolean

        fun onCarrierInfo(carrierInfo: NegotiatedCarrierInfo)

        fun onNegotiationFailed(error: NfcPresentationError)
    }

    override fun shouldPerformHandoverEngagement(): Boolean {
        val current = listener ?: return false
        return current.isListening() && appInForeground()
    }

    /**
     * The app manifest registers the mdoc AID from install, so a reader can
     * select this service while no tap is armed. The base class answers null
     * in that case, which tells Android that a response comes later, and the
     * reader waits until it times out. Answer "file not found" instead, so
     * the reader fails at once.
     */
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        if (!shouldPerformHandoverEngagement()) return STATUS_FILE_NOT_FOUND
        return super.processCommandApdu(commandApdu, extras)
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

        /** ISO 7816-4 status word 6A82. */
        private val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        fun componentName(context: Context): ComponentName =
            ComponentName(context, NfcPresentationService::class.java)
    }
}
