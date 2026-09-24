package com.spruceid.sprucekit_mobile

/**
 * Phase tracker for one NFC tap-to-share attempt.
 *
 * The HCE service, the NFC adapter broadcast, and the Dart side all call into
 * the adapter from different threads and in any order. This class decides
 * which of those events count, so the adapter only maps a `true` result to a
 * state update.
 */
internal class NfcEngagementCoordinator {
    enum class Phase {
        /** Nothing armed. Taps are ignored. */
        IDLE,

        /** The service answers APDUs. One tap moves to ENGAGED. */
        ARMED,

        /**
         * The handover completed and the BLE session owns the rest. The
         * service still answers APDUs, since the reader finishes its NDEF
         * read after the handover, but a second carrier info is ignored.
         */
        ENGAGED,
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** True while the HCE service must answer APDUs. */
    val isListening: Boolean
        get() = phase != Phase.IDLE

    /** Returns true when this call armed the service. */
    @Synchronized
    fun arm(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.ARMED
        return true
    }

    /** Returns true when the carrier info must start the BLE session. */
    @Synchronized
    fun onCarrierInfo(): Boolean {
        if (phase != Phase.ARMED) return false
        phase = Phase.ENGAGED
        return true
    }

    /** Returns true when the failure must reach the wallet as an error. */
    @Synchronized
    fun onNegotiationFailed(): Boolean {
        if (phase != Phase.ARMED) return false
        phase = Phase.IDLE
        return true
    }

    /** Returns true when the wallet must fall back to QR. */
    @Synchronized
    fun onNfcTurnedOff(): Boolean {
        if (phase != Phase.ARMED) return false
        phase = Phase.IDLE
        return true
    }

    /**
     * The BLE session moved past the NFC read: the reader sent its request,
     * or the session ended. Returns true when the NFC hooks must be released.
     */
    @Synchronized
    fun onSessionEnded(): Boolean {
        if (phase != Phase.ENGAGED) return false
        phase = Phase.IDLE
        return true
    }

    @Synchronized
    fun cancel() {
        phase = Phase.IDLE
    }
}
