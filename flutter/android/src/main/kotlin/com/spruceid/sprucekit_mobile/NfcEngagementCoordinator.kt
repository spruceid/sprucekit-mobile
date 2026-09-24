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

    /**
     * Changes on every phase change. A block that runs later captures it
     * first and compares it before it acts, so work queued for one attempt
     * never lands on the attempt that replaced it.
     */
    @Volatile
    var generation: Int = 0
        private set

    /** True while the HCE service must answer APDUs. */
    val isListening: Boolean
        get() = phase != Phase.IDLE

    private fun move(to: Phase) {
        phase = to
        generation++
    }

    /** Returns true when this call armed the service. */
    @Synchronized
    fun arm(): Boolean {
        if (phase != Phase.IDLE) return false
        move(Phase.ARMED)
        return true
    }

    /** Returns true when the carrier info must start the BLE session. */
    @Synchronized
    fun onCarrierInfo(): Boolean {
        if (phase != Phase.ARMED) return false
        move(Phase.ENGAGED)
        return true
    }

    /** Returns true when the failure must reach the wallet as an error. */
    @Synchronized
    fun onNegotiationFailed(): Boolean {
        if (phase != Phase.ARMED) return false
        move(Phase.IDLE)
        return true
    }

    /** Returns true when the wallet must fall back to QR. */
    @Synchronized
    fun onNfcTurnedOff(): Boolean {
        if (phase != Phase.ARMED) return false
        move(Phase.IDLE)
        return true
    }

    /** The generation of the engaged attempt, or null when no attempt is engaged. */
    @Synchronized
    fun engagedGeneration(): Int? = if (phase == Phase.ENGAGED) generation else null

    /**
     * The BLE session moved past the NFC read: the reader sent its request,
     * or the session ended. Returns true when the NFC hooks must be released.
     * `generation` pins the call to the attempt it was made for. A different
     * value means a cancel or a new attempt came first, and nothing happens.
     */
    @Synchronized
    fun onSessionEnded(generation: Int): Boolean {
        if (phase != Phase.ENGAGED || this.generation != generation) return false
        move(Phase.IDLE)
        return true
    }

    @Synchronized
    fun cancel() {
        if (phase == Phase.IDLE) return
        move(Phase.IDLE)
    }
}
