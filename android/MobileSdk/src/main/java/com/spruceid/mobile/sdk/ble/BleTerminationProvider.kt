package com.spruceid.mobile.sdk.ble

import android.util.Log

/**
 * Centralized BLE Session Termination Provider - ISO 18013-5 Section 8.3.3.1.1.7
 *
 * Provides centralized session termination logic per ISO 18013-5 requirements:
 * - Sends transport-specific session termination message (0x02) when required
 * - Handles proper termination flow for both Reader and Holder roles
 * - Integrates with state machine for automatic termination on terminal errors
 * - Ensures compliance with session management protocol
 *
 * @param stateMachine The BLE connection state machine instance
 * @param logger Logger instance for debugging termination events
 */
class BleTerminationProvider(
    private val stateMachine: BleConnectionStateMachine = BleConnectionStateMachine.getInstance(),
    private val logger: BleLogger = BleLogger.getInstance("BleTerminationProvider")
) {

    companion object {
        private const val TAG = "BleTerminationProvider"

        /**
         * ISO 18013-5 Section 8.3.3.1.1.7 - Transport Specific Session Termination
         * Message type for session termination
         */
        private const val SESSION_TERMINATION_MESSAGE_TYPE: Byte = 0x02
    }

    private var gattClientSender: ((ByteArray) -> Unit)? = null
    private var gattServerSender: ((ByteArray) -> Unit)? = null

    /**
     * Register GATT Client termination sender (for Holder role)
     * This will be called when session needs to be terminated from client side
     */
    fun registerGattClientSender(sender: (ByteArray) -> Unit) {
        gattClientSender = sender
        logger.d("GATT Client termination sender registered")
    }

    /**
     * Register GATT Server termination sender (for Reader role)
     * This will be called when session needs to be terminated from server side
     */
    fun registerGattServerSender(sender: (ByteArray) -> Unit) {
        gattServerSender = sender
        logger.d("GATT Server termination sender registered")
    }

    /**
     * Initialize termination provider with state machine integration
     * Sets up automatic termination callback for terminal errors
     */
    fun initialize() {
        stateMachine.setTerminationCallback {
            sendSessionTermination("Terminal error detected by state machine")
        }
        logger.i("BleTerminationProvider initialized with state machine integration")
    }

    /**
     * Send session termination message per ISO 18013-5 Section 8.3.3.1.1.7
     *
     * Sends the transport-specific session termination message (0x02) to notify
     * the peer that the session is ending. This is required by the ISO standard
     * for proper session management.
     *
     * @param reason Human-readable reason for termination (for logging)
     * @param force If true, send termination even if not connected (for cleanup)
     */
    fun sendSessionTermination(reason: String = "Session terminated", force: Boolean = false) {
        logger.i("Sending session termination: $reason")

        // Check if we should send termination
        if (!force && !shouldSendTermination()) {
            logger.d("Skipping session termination - not in appropriate state")
            return
        }

        try {
            val terminationMessage = createSessionTerminationMessage()
            var sent = false

            // Try to send via GATT Client (Holder role)
            gattClientSender?.let { sender ->
                try {
                    sender(terminationMessage)
                    sent = true
                    logger.d("Session termination sent via GATT Client")
                } catch (e: Exception) {
                    logger.w("Failed to send termination via GATT Client: ${e.message}")
                }
            }

            // Try to send via GATT Server (Reader role) if client failed or not available
            if (!sent) {
                gattServerSender?.let { sender ->
                    try {
                        sender(terminationMessage)
                        sent = true
                        logger.d("Session termination sent via GATT Server")
                    } catch (e: Exception) {
                        logger.w("Failed to send termination via GATT Server: ${e.message}")
                    }
                }
            }

            if (!sent) {
                logger.w("No termination sender available - session termination not sent")
            } else {
                logger.i("Session termination (0x02) sent successfully")

                // Reset to IDLE state after successful termination to allow new connections
                if (stateMachine.isInState(BleConnectionStateMachine.State.ERROR)) {
                    if (stateMachine.transitionTo(BleConnectionStateMachine.State.IDLE)) {
                        logger.d("State reset to IDLE after successful termination")
                    } else {
                        logger.w("Failed to reset to IDLE state after termination")
                    }
                }
            }

        } catch (e: Exception) {
            logger.e("Error sending session termination", e)
        }
    }

    /**
     * Handle error with automatic termination decision
     *
     * @param error The error that occurred
     * @param context Additional context about the error
     * @return true if session was terminated, false if error is recoverable
     */
    fun handleError(error: Throwable, context: String = ""): Boolean {
        val errorType = BleErrorClassifier.classifyError(error, context)

        logger.d("Handling error: ${error.message} (type: $errorType)")

        return when (errorType) {
            BleErrorClassifier.ErrorType.TERMINAL -> {
                logger.i("Terminal error detected - sending session termination")
                sendSessionTermination("Terminal error: ${error.message}")

                // Transition to error state (will not trigger callback since we already sent)
                stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
                true
            }

            BleErrorClassifier.ErrorType.RECOVERABLE -> {
                logger.d("Recoverable error - no session termination needed")
                false
            }
        }
    }

    /**
     * Check if session termination should be sent based on current state
     */
    private fun shouldSendTermination(): Boolean {
        val currentState = stateMachine.getState()

        return when (currentState) {
            BleConnectionStateMachine.State.CONNECTED,
            BleConnectionStateMachine.State.CONNECTING -> true

            BleConnectionStateMachine.State.DISCONNECTING,
            BleConnectionStateMachine.State.DISCONNECTED,
            BleConnectionStateMachine.State.ERROR -> false // Already terminating/terminated

            BleConnectionStateMachine.State.IDLE,
            BleConnectionStateMachine.State.SCANNING -> false // Not connected
        }
    }

    /**
     * Create the session termination message per ISO 18013-5
     *
     * @return ByteArray containing the 0x02 termination message
     */
    private fun createSessionTerminationMessage(): ByteArray {
        // ISO 18013-5 Section 8.3.3.1.1.7: Transport-specific session termination
        // Simple message with 0x02 message type
        return byteArrayOf(SESSION_TERMINATION_MESSAGE_TYPE)
    }
}