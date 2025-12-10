package com.spruceid.mobile.sdk.ble

import java.util.concurrent.TimeoutException

/**
 * Error Classification for BLE Session Management - ISO 18013-5 Section 8.3.3.1.1.7
 *
 * Classifies errors into recoverable and terminal categories to determine
 * whether session termination (0x02) should be sent per ISO 18013-5:
 * - Terminal errors: Require immediate session termination with 0x02 signal
 * - Recoverable errors: Allow retry without terminating the session
 */
object BleErrorClassifier {

    enum class ErrorType {
        /**
         * Terminal errors that require session termination per ISO 18013-5
         * These errors indicate the session cannot continue and 0x02 must be sent
         */
        TERMINAL,

        /**
         * Recoverable errors that allow retry without termination
         * These errors may be temporary and don't require session end
         */
        RECOVERABLE
    }

    /**
     * Classify an error to determine session termination requirements
     *
     * @param error The error/exception to classify
     * @param context Additional context about the error (optional)
     * @return ErrorType indicating whether termination is required
     */
    fun classifyError(error: Throwable, context: String = ""): ErrorType {
        return when (error) {
            // Security-related errors are always terminal per ISO 18013-5 Section 9
            is SecurityException -> ErrorType.TERMINAL

            // Authentication/authorization failures are terminal
            is IllegalStateException -> {
                if (error.message?.contains("authentication", ignoreCase = true) == true ||
                    error.message?.contains("authorization", ignoreCase = true) == true
                ) {
                    ErrorType.TERMINAL
                } else {
                    ErrorType.RECOVERABLE
                }
            }

            // Check for Bluetooth-related exceptions by class name since
            // BluetoothGattException/BluetoothException may not be available
            is RuntimeException -> {
                val className = error.javaClass.simpleName
                val message = error.message?.lowercase() ?: ""

                when {
                    className.contains("BluetoothGatt", ignoreCase = true) ||
                            className.contains("Gatt", ignoreCase = true) -> {
                        // GATT-specific error handling
                        classifyGattError(error)
                    }

                    className.contains("Bluetooth", ignoreCase = true) ||
                            message.contains("bluetooth") -> {
                        // General Bluetooth errors - adapter issues are terminal
                        if (message.contains("adapter") || message.contains("device not found")) {
                            ErrorType.TERMINAL
                        } else {
                            ErrorType.RECOVERABLE
                        }
                    }

                    message.contains("corrupt") || message.contains("parse") ||
                            message.contains("malformed") -> {
                        // Data corruption/parsing errors are terminal
                        ErrorType.TERMINAL
                    }

                    else -> ErrorType.RECOVERABLE
                }
            }

            // Timeout errors are generally recoverable (can retry)
            is TimeoutException -> ErrorType.RECOVERABLE

            // Cancellation is recoverable (user-initiated)
            is InterruptedException -> ErrorType.RECOVERABLE

            // Protocol violations are terminal
            is IllegalArgumentException -> {
                if (error.message?.contains("protocol", ignoreCase = true) == true ||
                    error.message?.contains("invalid", ignoreCase = true) == true
                ) {
                    ErrorType.TERMINAL
                } else {
                    ErrorType.RECOVERABLE
                }
            }

            // This case is now handled in the RuntimeException branch above

            // Out of memory, system errors are terminal
            is OutOfMemoryError,
            is VirtualMachineError -> ErrorType.TERMINAL

            // Network/IO errors are generally recoverable
            is java.io.IOException -> ErrorType.RECOVERABLE

            // Unknown errors default to terminal for safety
            else -> ErrorType.TERMINAL
        }
    }

    /**
     * Classify specific GATT errors based on Bluetooth specification
     */
    private fun classifyGattError(error: Throwable): ErrorType {
        // Note: BluetoothGattException doesn't expose error codes directly
        // We classify based on message content as a fallback
        val message = error.message?.lowercase() ?: ""

        return when {
            // Connection issues that might be temporary
            message.contains("connection timeout") ||
                    message.contains("connection lost") ||
                    message.contains("device disconnected") -> ErrorType.RECOVERABLE

            // Authentication/security failures are terminal
            message.contains("authentication failed") ||
                    message.contains("insufficient authentication") ||
                    message.contains("insufficient encryption") -> ErrorType.TERMINAL

            // Service/characteristic not found could be recoverable (discovery issue)
            message.contains("service not found") ||
                    message.contains("characteristic not found") -> ErrorType.RECOVERABLE

            // Write/read failures might be temporary
            message.contains("write failed") ||
                    message.contains("read failed") -> ErrorType.RECOVERABLE

            // Default GATT errors to terminal for safety
            else -> ErrorType.TERMINAL
        }
    }

    /**
     * Check if an error should trigger session termination
     */
    fun shouldTerminateSession(error: Throwable, context: String = ""): Boolean {
        return classifyError(error, context) == ErrorType.TERMINAL
    }

    /**
     * Check if an error allows for retry without termination
     */
    fun isRecoverable(error: Throwable, context: String = ""): Boolean {
        return classifyError(error, context) == ErrorType.RECOVERABLE
    }
}