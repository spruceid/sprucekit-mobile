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
    
    /**
     * Retry exhausted exception
     */
    class RetryExhaustedException(
        operation: String,
        attempts: Int
    ) : BleException("$operation failed after $attempts attempts")
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
            is SecurityException -> BleException.SecurityException(error.message ?: "Security error", error)
            is IllegalStateException -> BleException.InvalidStateException(
                BleConnectionStateMachine.State.ERROR,
                BleConnectionStateMachine.State.IDLE
            )
            is IllegalArgumentException -> BleException.ValidationException(error.message ?: "Validation error")
            else -> BleException.ConnectionException("$operation failed: ${error.message}", error)
        }
        
        // Log based on exception type
        when (bleException) {
            is BleException.SecurityException -> logger.e("Security error in $operation", bleException)
            is BleException.TimeoutException -> logger.w("Timeout in $operation", bleException)
            is BleException.ValidationException -> logger.w("Validation error in $operation", bleException)
            else -> logger.e("Error in $operation", bleException)
        }
        
        // Invoke callback if provided
        callback?.invoke(bleException)
    }
    
    /**
     * Convert GATT status code to exception
     */
    fun gattStatusToException(status: Int, operation: String): BleException? {
        return when (status) {
            0 -> null // GATT_SUCCESS
            1 -> BleException.ValidationException("Invalid handle for $operation")
            2 -> BleException.GattException(operation, status) // Read not permitted
            3 -> BleException.GattException(operation, status) // Write not permitted
            5 -> BleException.SecurityException("Insufficient authentication for $operation")
            6 -> BleException.GattException(operation, status) // Request not supported
            7 -> BleException.ValidationException("Invalid offset for $operation")
            8 -> BleException.SecurityException("Insufficient authorization for $operation")
            13 -> BleException.ValidationException("Invalid attribute length for $operation")
            15 -> BleException.SecurityException("Insufficient encryption for $operation")
            133 -> BleException.ConnectionException("Device disconnected during $operation")
            257 -> BleException.ConnectionException("Connection failed for $operation")
            else -> BleException.GattException(operation, status)
        }
    }
    
    /**
     * Check if error is recoverable
     */
    fun isRecoverable(error: Throwable): Boolean {
        return when (error) {
            is BleException.TimeoutException -> true
            is BleException.ConnectionException -> true
            is BleException.GattException -> error.message?.contains("133") == true // Connection issue
            is BleException.RetryExhaustedException -> false
            is BleException.SecurityException -> false
            is BleException.ValidationException -> false
            else -> false
        }
    }
}