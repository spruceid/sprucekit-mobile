package com.spruceid.mobile.sdk.ble

import kotlinx.coroutines.*
import kotlin.math.min

/**
 * Retry manager with exponential backoff for BLE operations
 */
class BleRetryManager(
    private val config: BleConfiguration = BleConfiguration(),
    private val logger: BleLogger = BleLogger.getInstance("BleRetryManager")
) {
    
    /**
     * Execute an operation with automatic retry on failure
     */
    suspend fun <T> executeWithRetry(
        operation: String,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        var currentDelay = config.initialRetryDelayMs
        var lastException: Exception? = null
        
        for (attempt in 1..config.maxConnectionRetries) {
            try {
                logger.d("Executing $operation (attempt $attempt/${config.maxConnectionRetries})")
                val result = block()
                logger.d("$operation succeeded on attempt $attempt")
                return@withContext Result.success(result)
            } catch (e: CancellationException) {
                // Don't retry on cancellation
                logger.d("$operation cancelled")
                throw e
            } catch (e: Exception) {
                lastException = e
                logger.w("$operation failed on attempt $attempt: ${e.message}")
                
                if (attempt < config.maxConnectionRetries) {
                    logger.d("Retrying $operation in ${currentDelay}ms")
                    delay(currentDelay)
                    currentDelay = min(
                        (currentDelay * config.retryBackoffMultiplier).toLong(),
                        config.maxRetryDelayMs
                    )
                }
            }
        }
        
        logger.e("$operation failed after ${config.maxConnectionRetries} attempts", lastException)
        return@withContext Result.failure(
            lastException ?: Exception("Operation failed after ${config.maxConnectionRetries} attempts")
        )
    }
    
    /**
     * Execute an operation with timeout
     */
    suspend fun <T> executeWithTimeout(
        operation: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                logger.d("Executing $operation with timeout ${timeoutMs}ms")
                val result = block()
                logger.d("$operation completed successfully")
                Result.success(result)
            }
        } catch (e: TimeoutCancellationException) {
            logger.e("$operation timed out after ${timeoutMs}ms")
            Result.failure(Exception("$operation timed out after ${timeoutMs}ms"))
        } catch (e: Exception) {
            logger.e("$operation failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Execute an operation with both retry and timeout
     */
    suspend fun <T> executeWithRetryAndTimeout(
        operation: String,
        timeoutMs: Long = config.connectionTimeoutMs,
        block: suspend () -> T
    ): Result<T> {
        return executeWithRetry(operation) {
            executeWithTimeout(operation, timeoutMs, block).getOrThrow()
        }
    }
}