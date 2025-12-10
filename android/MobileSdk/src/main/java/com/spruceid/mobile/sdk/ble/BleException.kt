package com.spruceid.mobile.sdk.ble

/**
 * Base exception for BLE operations
 */
sealed class BleException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Connection-related exceptions
     */
    class ConnectionException(
        message: String,
        cause: Throwable? = null
    ) : BleException(message, cause)

    /**
     * Timeout exceptions
     */
    class TimeoutException(
        operation: String,
        timeoutMs: Long
    ) : BleException("$operation timed out after ${timeoutMs}ms")

    /**
     * Invalid state exceptions
     */
    class InvalidStateException(
        currentState: BleConnectionStateMachine.State,
        attemptedTransition: BleConnectionStateMachine.State
    ) : BleException("Invalid state transition from $currentState to $attemptedTransition")

    /**
     * Security exceptions
     */
    class SecurityException(
        message: String,
        cause: Throwable? = null
    ) : BleException(message, cause)

    /**
     * Data validation exceptions
     */
    class ValidationException(
        message: String
    ) : BleException(message)

    /**
     * GATT operation exceptions
     */
    class GattException(
        operation: String,
        status: Int
    ) : BleException("GATT operation '$operation' failed with status $status")

    /**
     * L2CAP exceptions
     */
    class L2CAPException(
        message: String,
        cause: Throwable? = null
    ) : BleException(message, cause)

    /**
     * Resource exceptions
     */
    class ResourceException(
        resource: String,
        message: String
    ) : BleException("Resource '$resource': $message")
}

/**
 * Error handler for consistent error processing
 */
class BleErrorHandler(
    private val logger: BleLogger
) {

    /**
     * Handle error with appropriate logging and recovery
     */
    fun handleError(
        error: Throwable,
        operation: String,
        callback: ((BleException) -> Unit)? = null
    ) {
        val bleException = when (error) {
            is BleException -> error
            is SecurityException -> BleException.SecurityException(
                error.message ?: "Security error", error
            )

            is IllegalStateException -> BleException.InvalidStateException(
                BleConnectionStateMachine.State.ERROR,
                BleConnectionStateMachine.State.IDLE
            )

            is IllegalArgumentException -> BleException.ValidationException(
                error.message ?: "Validation error"
            )

            else -> BleException.ConnectionException("$operation failed: ${error.message}", error)
        }

        // Log based on exception type
        when (bleException) {
            is BleException.SecurityException -> logger.e(
                "Security error in $operation",
                bleException
            )

            is BleException.TimeoutException -> logger.w("Timeout in $operation", bleException)
            is BleException.ValidationException -> logger.w(
                "Validation error in $operation",
                bleException
            )

            else -> logger.e("Error in $operation", bleException)
        }

        // Invoke callback if provided
        callback?.invoke(bleException)
    }
}