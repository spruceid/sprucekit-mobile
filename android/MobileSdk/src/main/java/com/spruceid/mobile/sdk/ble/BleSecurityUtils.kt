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
}