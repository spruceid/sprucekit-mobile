package com.spruceid.mobile.sdk.ble

import android.util.Log

/**
 * Secure logger for BLE operations that sanitizes sensitive data
 */
class BleLogger(
    private val tag: String,
    private val config: BleConfiguration = BleConfiguration()
) {
    
    /**
     * Log verbose message
     */
    fun v(message: String) {
        if (config.logLevel.ordinal >= BleConfiguration.LogLevel.VERBOSE.ordinal) {
            Log.v(tag, sanitize(message))
        }
    }
    
    /**
     * Log debug message
     */
    fun d(message: String) {
        if (config.logLevel.ordinal >= BleConfiguration.LogLevel.DEBUG.ordinal) {
            Log.d(tag, sanitize(message))
        }
    }
    
    /**
     * Log info message
     */
    fun i(message: String) {
        if (config.logLevel.ordinal >= BleConfiguration.LogLevel.INFO.ordinal) {
            Log.i(tag, sanitize(message))
        }
    }
    
    /**
     * Log warning message
     */
    fun w(message: String, throwable: Throwable? = null) {
        if (config.logLevel.ordinal >= BleConfiguration.LogLevel.WARN.ordinal) {
            if (throwable != null) {
                Log.w(tag, sanitize(message), throwable)
            } else {
                Log.w(tag, sanitize(message))
            }
        }
    }
    
    /**
     * Log error message
     */
    fun e(message: String, throwable: Throwable? = null) {
        if (config.logLevel.ordinal >= BleConfiguration.LogLevel.ERROR.ordinal) {
            if (throwable != null) {
                Log.e(tag, sanitize(message), throwable)
            } else {
                Log.e(tag, sanitize(message))
            }
        }
    }
    
    /**
     * Log data transfer (sanitizes payload data)
     */
    fun logDataTransfer(direction: String, size: Int, mode: String = "GATT") {
        if (config.logLevel.ordinal >= BleConfiguration.LogLevel.DEBUG.ordinal) {
            Log.d(tag, "$direction $size bytes via $mode")
        }
    }
    
    /**
     * Log hex data (only if explicitly allowed)
     */
    fun logHexData(label: String, data: ByteArray) {
        if (config.logSensitiveData && config.logLevel.ordinal >= BleConfiguration.LogLevel.VERBOSE.ordinal) {
            Log.v(tag, "$label: ${byteArrayToHex(data)}")
        } else if (config.logLevel.ordinal >= BleConfiguration.LogLevel.DEBUG.ordinal) {
            Log.d(tag, "$label: [${data.size} bytes]")
        }
    }
    
    /**
     * Sanitize message to remove potentially sensitive data
     */
    private fun sanitize(message: String): String {
        if (config.logSensitiveData) {
            return message
        }
        
        // Remove hex strings (potential keys/credentials)
        var sanitized = message.replace(Regex("\\b[0-9a-fA-F]{8,}\\b"), "[REDACTED]")
        
        // Remove byte array contents
        sanitized = sanitized.replace(Regex("\\[[-0-9, ]+\\]"), "[REDACTED]")
        
        // Remove potential identifiers (UUIDs)
        sanitized = sanitized.replace(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            "[UUID]"
        )
        
        return sanitized
    }
    
    /**
     * Convert byte array to hex string
     */
    private fun byteArrayToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    companion object {
        private val loggers = mutableMapOf<String, BleLogger>()
        
        /**
         * Get or create a logger instance
         */
        fun getInstance(tag: String, config: BleConfiguration = BleConfiguration()): BleLogger {
            return loggers.getOrPut(tag) { BleLogger(tag, config) }
        }
    }
}