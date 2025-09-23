package com.spruceid.mobile.sdk.ble

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Security utilities for BLE operations following ISO 18013-5 security requirements
 *
 * Provides cryptographic operations hardened against timing attacks and other
 * side-channel attacks that could compromise mDL Reader authentication and
 * data integrity verification.
 *
 * Key features:
 * - Constant-time byte array comparison
 * - Timing attack protection with random delays
 * - Secure input validation with size limits
 * - Memory-safe operations to prevent buffer overflows
 */
object BleSecurityUtils {

    private val secureRandom = SecureRandom()
    private val logger = BleLogger.getInstance("BleSecurityUtils")

    /**
     * Constant-time byte array comparison to prevent timing attacks
     *
     * Used for comparing Ident values in Reader authentication per
     * ISO 18013-5 Section 8.3.3.1.1.3. Standard equality operators
     * can leak information about where arrays differ through timing.
     *
     * @param a First array to compare
     * @param b Second array to compare
     * @return true if arrays are equal, false otherwise
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) {
            return a === b
        }

        // Arrays of different lengths are never equal
        if (a.size != b.size) {
            return false
        }

        // Use MessageDigest.isEqual() which implements constant-time comparison
        return try {
            MessageDigest.isEqual(a, b)
        } catch (e: Exception) {
            logger.e("Error in constant-time comparison", e)
            false
        }
    }

    /**
     * Secure comparison with additional timing obfuscation
     *
     * Adds random delay to further obfuscate timing patterns
     * when constant-time comparison is not sufficient.
     *
     * @param a First array to compare
     * @param b Second array to compare
     * @param config BLE configuration for timing parameters
     * @return true if arrays are equal, false otherwise
     */
    fun secureEquals(a: ByteArray?, b: ByteArray?, config: BleConfiguration): Boolean {
        val startTime = SystemClock.elapsedRealtime()

        // Perform constant-time comparison
        val result = constantTimeEquals(a, b)

        // Add random timing obfuscation if enabled
        if (config.randomizeResponseTiming) {
            addRandomDelay()
        }

        val elapsedTime = SystemClock.elapsedRealtime() - startTime
        logger.d("Secure comparison completed in ${elapsedTime}ms")

        return result
    }

    /**
     * Validate input buffer size to prevent buffer overflow attacks
     *
     * Ensures incoming data doesn't exceed reasonable limits to prevent
     * DoS attacks or memory exhaustion.
     *
     * @param data Data to validate
     * @param maxSize Maximum allowed size in bytes
     * @param context Context for logging (e.g., "Ident validation", "L2CAP message")
     * @throws SecurityException if data exceeds limits
     */
    fun validateInputSize(data: ByteArray?, maxSize: Int, context: String) {
        when {
            data == null -> {
                throw SecurityException("$context: null data not allowed")
            }
            data.isEmpty() -> {
                throw SecurityException("$context: empty data not allowed")
            }
            data.size > maxSize -> {
                logger.w("$context: oversized input rejected (${data.size} > $maxSize bytes)")
                throw SecurityException("$context: input size ${data.size} exceeds maximum $maxSize")
            }
        }

        logger.d("$context: input size validation passed (${data.size} bytes)")
    }

    /**
     * Secure buffer allocation with size limits
     *
     * Allocates buffers with bounds checking to prevent memory exhaustion
     * attacks via malformed message length fields.
     *
     * @param requestedSize Requested buffer size
     * @param maxAllowedSize Maximum allowed allocation
     * @param context Context for logging
     * @return Allocated byte array
     * @throws SecurityException if size is invalid
     */
    fun secureAllocateBuffer(requestedSize: Int, maxAllowedSize: Int, context: String): ByteArray {
        when {
            requestedSize <= 0 -> {
                throw SecurityException("$context: invalid buffer size $requestedSize")
            }
            requestedSize > maxAllowedSize -> {
                logger.w("$context: buffer allocation rejected (${requestedSize} > $maxAllowedSize bytes)")
                throw SecurityException("$context: requested buffer size $requestedSize exceeds limit $maxAllowedSize")
            }
        }

        return try {
            ByteArray(requestedSize)
        } catch (e: OutOfMemoryError) {
            logger.e("$context: failed to allocate $requestedSize bytes", e)
            throw SecurityException("$context: failed to allocate buffer of size $requestedSize")
        }
    }

    /**
     * Add random delay for timing attack protection
     *
     * Introduces small random delays to obscure timing patterns
     * that could leak information about internal operations.
     */
    private fun addRandomDelay() {
        try {
            // Random delay between 1-5 milliseconds
            val delayMs = secureRandom.nextInt(5) + 1
            Thread.sleep(delayMs.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.d("Random delay interrupted")
        }
    }

    /**
     * Generate cryptographically secure random bytes
     *
     * @param size Number of bytes to generate
     * @return Secure random byte array
     */
    fun generateSecureRandom(size: Int): ByteArray {
        if (size <= 0 || size > 1024) {
            throw IllegalArgumentException("Invalid random size: $size (must be 1-1024)")
        }

        return ByteArray(size).apply {
            secureRandom.nextBytes(this)
        }
    }

    /**
     * Securely clear sensitive data from memory
     *
     * Overwrites byte arrays containing sensitive data (keys, identifiers)
     * to prevent memory-based attacks or accidental disclosure.
     *
     * @param sensitiveData Array to clear
     */
    fun secureErase(sensitiveData: ByteArray?) {
        sensitiveData?.let { data ->
            // Overwrite with random data first
            secureRandom.nextBytes(data)
            // Then overwrite with zeros
            data.fill(0)

            logger.d("Securely erased ${data.size} bytes")
        }
    }

    /**
     * Validate message integrity using simple checksum
     *
     * Provides basic integrity checking for BLE messages to detect
     * corruption during transmission. Not cryptographically secure,
     * but sufficient for detecting accidental corruption.
     *
     * @param data Message data
     * @return Simple checksum value
     */
    fun calculateSimpleChecksum(data: ByteArray): Int {
        var checksum = 0
        for (byte in data) {
            checksum = (checksum + (byte.toInt() and 0xFF)) and 0xFFFF
        }
        return checksum
    }

    /**
     * Validate BLE security requirements
     *
     * Checks if the current BLE connection meets minimum security
     * requirements for mDL transactions per ISO 18013-5.
     *
     * @param securityLevel Current BLE security level
     * @param requireEncryption Whether encryption is mandatory
     * @param requireAuthentication Whether authentication is mandatory
     * @return true if security requirements are met
     */
    fun validateBleSecurityLevel(
        securityLevel: Int,
        requireEncryption: Boolean = true,
        requireAuthentication: Boolean = true
    ): Boolean {
        // These constants would typically come from BluetoothDevice or BluetoothGatt
        val hasEncryption = securityLevel >= 2  // Placeholder - use actual constants
        val hasAuthentication = securityLevel >= 3  // Placeholder - use actual constants

        val securityOk = (!requireEncryption || hasEncryption) &&
                        (!requireAuthentication || hasAuthentication)

        if (!securityOk) {
            logger.w("BLE security requirements not met: level=$securityLevel, " +
                    "encryption=$hasEncryption, authentication=$hasAuthentication")
        }

        return securityOk
    }

    /**
     * Sanitize log data to prevent sensitive information leakage
     *
     * Removes or masks sensitive information from data before logging.
     * Used to prevent accidental disclosure of mDL data, keys, or
     * personal information in log files.
     *
     * @param data Raw data to sanitize
     * @param maskLength Number of characters to show before masking
     * @return Sanitized string safe for logging
     */
    fun sanitizeForLogging(data: ByteArray?, maskLength: Int = 4): String {
        return when {
            data == null -> "null"
            data.isEmpty() -> "empty"
            data.size <= maskLength -> "[MASKED:${data.size}]"
            else -> {
                val prefix = data.take(maskLength)
                    .joinToString("") { "%02x".format(it) }
                "$prefix...[MASKED:${data.size-maskLength}]"
            }
        }
    }

    /**
     * Security configuration validation
     *
     * Validates that BLE configuration meets security requirements
     * for production mDL deployments.
     *
     * @param config BLE configuration to validate
     * @return List of security warnings/errors
     */
    fun validateSecurityConfiguration(config: BleConfiguration): List<String> {
        val issues = mutableListOf<String>()

        if (!config.randomizeResponseTiming) {
            issues.add("Timing attack protection disabled")
        }

        if (config.logSensitiveData) {
            issues.add("Sensitive data logging enabled (not recommended for production)")
        }

        if (config.connectionTimeoutMs > 30000) {
            issues.add("Connection timeout too long (DoS risk)")
        }

        if (config.maxConnectionRetries > 10) {
            issues.add("Too many retry attempts configured (DoS risk)")
        }

        if (config.messageTimeoutMs > 60000) {
            issues.add("Message timeout too long (resource exhaustion risk)")
        }

        return issues
    }
}