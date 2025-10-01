package com.spruceid.mobile.sdk.ble

/**
 * Configuration for BLE operations with sensible defaults following ISO 18013-5
 */
data class BleConfiguration(
    // Connection parameters
    val scanTimeoutMs: Long = 30000L,  // 30 seconds per ISO 18013-5
    val connectionTimeoutMs: Long = 10000L,  // 10 seconds
    val disconnectTimeoutMs: Long = 5000L,  // 5 seconds

    // Retry configuration
    val maxConnectionRetries: Int = 3, // Minimum 1 - Try once
    val initialRetryDelayMs: Long = 1000L,
    val maxRetryDelayMs: Long = 8000L,
    val retryBackoffMultiplier: Double = 2.0,

    // MTU configuration
    val preferredMtu: Int = 515,  // Maximum per ISO 18013-5
    val defaultMtu: Int = 23,  // BLE minimum

    // L2CAP configuration
    val useL2CAP: L2CAPMode = L2CAPMode.ALWAYS,
    val l2capBufferSize: Int = 65536,  // 64KB
    val l2capReadTimeoutMs: Long = 500L,
    val l2capConnectionTimeoutMs: Long = 5000L,

    // Transfer configuration  
    val messageTimeoutMs: Long = 30000L,  // 30 seconds for complete message
    val chunkTimeoutMs: Long = 5000L,  // 5 seconds per chunk

    // Logging configuration
    val logLevel: LogLevel = LogLevel.DEBUG,
    val logSensitiveData: Boolean = false,

    // Timing attack protection
    val randomizeResponseTiming: Boolean = true,

    // Additional security parameters
    val validateCborMessages: Boolean = true,
    val minAcceptableMtu: Int = 23,  // BLE minimum MTU
    val maxMessageSize: Int = 65536,  // 64KB maximum

    // Thread pool configuration
    val maxThreadPoolSize: Int = 4,
    val threadKeepAliveTimeMs: Long = 30000L,

    // Performance monitoring
    val enableMetrics: Boolean = false,
    val metricsRetentionMs: Long = 300000L  // 5 minutes
) {
    enum class L2CAPMode {
        ALWAYS,      // Always use L2CAP (fail if not available)
        IF_AVAILABLE, // Use if available, fall back to GATT
        NEVER        // Never use L2CAP
    }

    enum class LogLevel {
        NONE,
        ERROR,
        WARN,
        INFO,
        DEBUG,
        VERBOSE
    }
}
