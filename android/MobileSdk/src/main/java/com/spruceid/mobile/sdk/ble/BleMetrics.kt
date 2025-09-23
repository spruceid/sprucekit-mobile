package com.spruceid.mobile.sdk.ble

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * Performance monitoring and metrics collection for BLE operations
 *
 * Tracks key performance indicators for mDL BLE operations:
 * - Connection establishment time
 * - Data transfer rates and throughput
 * - Error rates by category
 * - Memory usage patterns
 * - Thread pool utilization
 * - Security operation timing
 *
 * Provides both real-time metrics and historical analysis for
 * performance optimization and debugging.
 */
class BleMetrics private constructor(
    private val config: BleConfiguration = BleConfiguration()
) {

    companion object {
        @Volatile
        private var INSTANCE: BleMetrics? = null

        fun getInstance(config: BleConfiguration = BleConfiguration()): BleMetrics {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleMetrics(config).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.shutdown()
                INSTANCE = null
            }
        }
    }

    private val logger = BleLogger.getInstance("BleMetrics", config)
    private val threadPool = BleThreadPool.getInstance(config)
    private val metricsScope = threadPool.timerScope

    // Metrics storage
    private val connectionMetrics = ConcurrentHashMap<String, ConnectionMetric>()
    private val transferMetrics = ConcurrentHashMap<String, TransferMetric>()
    private val errorMetrics = ConcurrentHashMap<String, ErrorMetric>()
    private val performanceCounters = ConcurrentHashMap<String, AtomicLong>()

    // Cleanup job for old metrics
    private var cleanupJob: Job? = null

    init {
        if (config.enableMetrics) {
            logger.i("BLE metrics collection enabled")
            initializeCounters()
            startCleanupTask()
        } else {
            logger.d("BLE metrics collection disabled")
        }
    }

    private fun initializeCounters() {
        // Connection counters
        performanceCounters["connections_attempted"] = AtomicLong(0)
        performanceCounters["connections_successful"] = AtomicLong(0)
        performanceCounters["connections_failed"] = AtomicLong(0)
        performanceCounters["disconnections"] = AtomicLong(0)

        // Transfer counters
        performanceCounters["bytes_sent"] = AtomicLong(0)
        performanceCounters["bytes_received"] = AtomicLong(0)
        performanceCounters["messages_sent"] = AtomicLong(0)
        performanceCounters["messages_received"] = AtomicLong(0)

        // Error counters
        performanceCounters["errors_total"] = AtomicLong(0)
        performanceCounters["errors_recoverable"] = AtomicLong(0)
        performanceCounters["errors_terminal"] = AtomicLong(0)

        // Security counters
        performanceCounters["ident_validations"] = AtomicLong(0)
        performanceCounters["ident_failures"] = AtomicLong(0)
        performanceCounters["security_violations"] = AtomicLong(0)
    }

    private fun startCleanupTask() {
        cleanupJob = metricsScope.launch {
            while (isActive) {
                try {
                    delay(config.metricsRetentionMs)
                    cleanupOldMetrics()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.e("Error in metrics cleanup task", e)
                }
            }
        }
    }

    /**
     * Record connection attempt
     */
    fun recordConnectionAttempt(connectionId: String, isReader: Boolean) {
        if (!config.enableMetrics) return

        performanceCounters["connections_attempted"]?.incrementAndGet()
        connectionMetrics[connectionId] = ConnectionMetric(
            connectionId = connectionId,
            isReader = isReader,
            startTime = TimeSource.Monotonic.markNow()
        )
    }

    /**
     * Record successful connection
     */
    fun recordConnectionSuccess(connectionId: String, mtu: Int, useL2CAP: Boolean) {
        if (!config.enableMetrics) return

        performanceCounters["connections_successful"]?.incrementAndGet()
        connectionMetrics[connectionId]?.let { metric ->
            metric.connectTime = metric.startTime.elapsedNow()
            metric.mtu = mtu
            metric.useL2CAP = useL2CAP
            metric.status = ConnectionStatus.CONNECTED
        }

        logger.d("Connection $connectionId established in ${connectionMetrics[connectionId]?.connectTime}")
    }

    /**
     * Record connection failure
     */
    fun recordConnectionFailure(connectionId: String, error: String) {
        if (!config.enableMetrics) return

        performanceCounters["connections_failed"]?.incrementAndGet()
        connectionMetrics[connectionId]?.let { metric ->
            metric.connectTime = metric.startTime.elapsedNow()
            metric.status = ConnectionStatus.FAILED
            metric.errorMessage = error
        }
    }

    /**
     * Record disconnection
     */
    fun recordDisconnection(connectionId: String, reason: String) {
        if (!config.enableMetrics) return

        performanceCounters["disconnections"]?.incrementAndGet()
        connectionMetrics[connectionId]?.let { metric ->
            metric.status = ConnectionStatus.DISCONNECTED
            metric.disconnectReason = reason
            metric.totalConnectionTime = metric.startTime.elapsedNow()
        }
    }

    /**
     * Record data transfer start
     */
    fun recordTransferStart(transferId: String, direction: TransferDirection, expectedBytes: Int) {
        if (!config.enableMetrics) return

        transferMetrics[transferId] = TransferMetric(
            transferId = transferId,
            direction = direction,
            expectedBytes = expectedBytes,
            startTime = TimeSource.Monotonic.markNow()
        )
    }

    /**
     * Record transfer progress
     */
    fun recordTransferProgress(transferId: String, bytesTransferred: Int) {
        if (!config.enableMetrics) return

        transferMetrics[transferId]?.let { metric ->
            metric.bytesTransferred = bytesTransferred
            metric.lastProgressTime = TimeSource.Monotonic.markNow()
        }
    }

    /**
     * Record completed transfer
     */
    fun recordTransferComplete(transferId: String, totalBytes: Int, chunks: Int = 1) {
        if (!config.enableMetrics) return

        transferMetrics[transferId]?.let { metric ->
            metric.bytesTransferred = totalBytes
            metric.chunks = chunks
            metric.transferTime = metric.startTime.elapsedNow()
            metric.status = TransferStatus.COMPLETED

            // Update global counters
            when (metric.direction) {
                TransferDirection.SEND -> {
                    performanceCounters["bytes_sent"]?.addAndGet(totalBytes.toLong())
                    performanceCounters["messages_sent"]?.incrementAndGet()
                }
                TransferDirection.RECEIVE -> {
                    performanceCounters["bytes_received"]?.addAndGet(totalBytes.toLong())
                    performanceCounters["messages_received"]?.incrementAndGet()
                }
            }

            logger.d("Transfer $transferId completed: ${totalBytes} bytes in ${metric.transferTime}")
        }
    }

    /**
     * Record transfer failure
     */
    fun recordTransferFailure(transferId: String, error: String) {
        if (!config.enableMetrics) return

        transferMetrics[transferId]?.let { metric ->
            metric.status = TransferStatus.FAILED
            metric.errorMessage = error
            metric.transferTime = metric.startTime.elapsedNow()
        }
    }

    /**
     * Record error occurrence
     */
    fun recordError(
        errorId: String,
        category: String,
        message: String,
        isTerminal: Boolean,
        context: String = ""
    ) {
        if (!config.enableMetrics) return

        performanceCounters["errors_total"]?.incrementAndGet()
        if (isTerminal) {
            performanceCounters["errors_terminal"]?.incrementAndGet()
        } else {
            performanceCounters["errors_recoverable"]?.incrementAndGet()
        }

        errorMetrics[errorId] = ErrorMetric(
            errorId = errorId,
            category = category,
            message = message,
            isTerminal = isTerminal,
            context = context,
            timestamp = TimeSource.Monotonic.markNow()
        )
    }

    /**
     * Record Ident validation
     */
    fun recordIdentValidation(success: Boolean, timeTaken: kotlin.time.Duration) {
        if (!config.enableMetrics) return

        performanceCounters["ident_validations"]?.incrementAndGet()
        if (!success) {
            performanceCounters["ident_failures"]?.incrementAndGet()
            performanceCounters["security_violations"]?.incrementAndGet()
        }

        logger.d("Ident validation ${if (success) "passed" else "failed"} in $timeTaken")
    }

    /**
     * Get connection statistics
     */
    fun getConnectionStatistics(): ConnectionStatistics {
        val attempted = performanceCounters["connections_attempted"]?.get() ?: 0
        val successful = performanceCounters["connections_successful"]?.get() ?: 0
        val failed = performanceCounters["connections_failed"]?.get() ?: 0

        val activeConnections = connectionMetrics.values.count { it.status == ConnectionStatus.CONNECTED }
        val avgConnectTime = connectionMetrics.values
            .filter { it.connectTime != null }
            .mapNotNull { it.connectTime?.inWholeMilliseconds }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        return ConnectionStatistics(
            attempted = attempted,
            successful = successful,
            failed = failed,
            active = activeConnections,
            successRate = if (attempted > 0) (successful.toDouble() / attempted) * 100 else 0.0,
            averageConnectTimeMs = avgConnectTime
        )
    }

    /**
     * Get transfer statistics
     */
    fun getTransferStatistics(): TransferStatistics {
        val bytesSent = performanceCounters["bytes_sent"]?.get() ?: 0
        val bytesReceived = performanceCounters["bytes_received"]?.get() ?: 0
        val messagesSent = performanceCounters["messages_sent"]?.get() ?: 0
        val messagesReceived = performanceCounters["messages_received"]?.get() ?: 0

        val completedTransfers = transferMetrics.values.filter { it.status == TransferStatus.COMPLETED }
        val avgThroughput = completedTransfers
            .mapNotNull { metric ->
                metric.transferTime?.let { time ->
                    if (time.inWholeMilliseconds > 0) {
                        (metric.bytesTransferred.toDouble() / time.inWholeSeconds) * 8 // bits per second
                    } else null
                }
            }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        return TransferStatistics(
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            averageThroughputBps = avgThroughput
        )
    }

    /**
     * Get error statistics
     */
    fun getErrorStatistics(): ErrorStatistics {
        val total = performanceCounters["errors_total"]?.get() ?: 0
        val recoverable = performanceCounters["errors_recoverable"]?.get() ?: 0
        val terminal = performanceCounters["errors_terminal"]?.get() ?: 0
        val securityViolations = performanceCounters["security_violations"]?.get() ?: 0

        val errorsByCategory = errorMetrics.values
            .groupBy { it.category }
            .mapValues { it.value.size }

        return ErrorStatistics(
            total = total,
            recoverable = recoverable,
            terminal = terminal,
            securityViolations = securityViolations,
            errorsByCategory = errorsByCategory
        )
    }

    /**
     * Get overall system health metrics
     */
    fun getSystemHealth(): SystemHealth {
        val connectionStats = getConnectionStatistics()
        val transferStats = getTransferStatistics()
        val errorStats = getErrorStatistics()
        val threadPoolStats = threadPool.getStatistics()

        // Calculate health score (0-100)
        val connectionHealth = minOf(connectionStats.successRate, 100.0)
        val errorHealth = maxOf(0.0, 100.0 - (errorStats.terminal.toDouble() / maxOf(1, errorStats.total) * 100))
        val threadPoolHealth = if (threadPool.isHealthy()) 100.0 else 50.0

        val overallHealth = (connectionHealth + errorHealth + threadPoolHealth) / 3.0

        return SystemHealth(
            overallScore = overallHealth,
            connectionHealth = connectionHealth,
            errorHealth = errorHealth,
            threadPoolHealth = threadPoolHealth,
            activeConnections = connectionStats.active,
            totalErrors = errorStats.total,
            memoryUsageKb = estimateMemoryUsage()
        )
    }

    /**
     * Export metrics for external analysis
     */
    fun exportMetrics(): MetricsExport {
        return MetricsExport(
            timestamp = System.currentTimeMillis(),
            connections = getConnectionStatistics(),
            transfers = getTransferStatistics(),
            errors = getErrorStatistics(),
            systemHealth = getSystemHealth(),
            threadPoolStats = threadPool.getStatistics(),
            counters = performanceCounters.mapValues { it.value.get() }
        )
    }

    private fun cleanupOldMetrics() {
        val cutoffTime = TimeSource.Monotonic.markNow() - kotlin.time.Duration.parse("${config.metricsRetentionMs}ms")

        // Clean up old connection metrics
        val oldConnections = connectionMetrics.filterValues { metric ->
            metric.startTime < cutoffTime && metric.status != ConnectionStatus.CONNECTED
        }
        oldConnections.keys.forEach { connectionMetrics.remove(it) }

        // Clean up old transfer metrics
        val oldTransfers = transferMetrics.filterValues { metric ->
            metric.startTime < cutoffTime && metric.status != TransferStatus.IN_PROGRESS
        }
        oldTransfers.keys.forEach { transferMetrics.remove(it) }

        // Clean up old error metrics
        val oldErrors = errorMetrics.filterValues { metric ->
            metric.timestamp < cutoffTime
        }
        oldErrors.keys.forEach { errorMetrics.remove(it) }

        if (oldConnections.isNotEmpty() || oldTransfers.isNotEmpty() || oldErrors.isNotEmpty()) {
            logger.d("Cleaned up ${oldConnections.size} connections, ${oldTransfers.size} transfers, ${oldErrors.size} errors")
        }
    }

    private fun estimateMemoryUsage(): Long {
        return (connectionMetrics.size * 200 + // ~200 bytes per connection metric
                transferMetrics.size * 150 + // ~150 bytes per transfer metric
                errorMetrics.size * 300 + // ~300 bytes per error metric
                performanceCounters.size * 50) // ~50 bytes per counter
            .toLong()
    }

    fun shutdown() {
        cleanupJob?.cancel()
        if (config.enableMetrics) {
            logger.i("BLE metrics collection shut down")
        }
    }

    // Data classes for metrics
    data class ConnectionMetric(
        val connectionId: String,
        val isReader: Boolean,
        val startTime: TimeSource.Monotonic.ValueTimeMark,
        var connectTime: kotlin.time.Duration? = null,
        var totalConnectionTime: kotlin.time.Duration? = null,
        var mtu: Int = 0,
        var useL2CAP: Boolean = false,
        var status: ConnectionStatus = ConnectionStatus.CONNECTING,
        var errorMessage: String? = null,
        var disconnectReason: String? = null
    )

    data class TransferMetric(
        val transferId: String,
        val direction: TransferDirection,
        val expectedBytes: Int,
        val startTime: TimeSource.Monotonic.ValueTimeMark,
        var lastProgressTime: TimeSource.Monotonic.ValueTimeMark = startTime,
        var bytesTransferred: Int = 0,
        var chunks: Int = 0,
        var transferTime: kotlin.time.Duration? = null,
        var status: TransferStatus = TransferStatus.IN_PROGRESS,
        var errorMessage: String? = null
    )

    data class ErrorMetric(
        val errorId: String,
        val category: String,
        val message: String,
        val isTerminal: Boolean,
        val context: String,
        val timestamp: TimeSource.Monotonic.ValueTimeMark
    )

    enum class ConnectionStatus { CONNECTING, CONNECTED, FAILED, DISCONNECTED }
    enum class TransferDirection { SEND, RECEIVE }
    enum class TransferStatus { IN_PROGRESS, COMPLETED, FAILED }

    data class ConnectionStatistics(
        val attempted: Long,
        val successful: Long,
        val failed: Long,
        val active: Int,
        val successRate: Double,
        val averageConnectTimeMs: Double
    )

    data class TransferStatistics(
        val bytesSent: Long,
        val bytesReceived: Long,
        val messagesSent: Long,
        val messagesReceived: Long,
        val averageThroughputBps: Double
    )

    data class ErrorStatistics(
        val total: Long,
        val recoverable: Long,
        val terminal: Long,
        val securityViolations: Long,
        val errorsByCategory: Map<String, Int>
    )

    data class SystemHealth(
        val overallScore: Double,
        val connectionHealth: Double,
        val errorHealth: Double,
        val threadPoolHealth: Double,
        val activeConnections: Int,
        val totalErrors: Long,
        val memoryUsageKb: Long
    )

    data class MetricsExport(
        val timestamp: Long,
        val connections: ConnectionStatistics,
        val transfers: TransferStatistics,
        val errors: ErrorStatistics,
        val systemHealth: SystemHealth,
        val threadPoolStats: BleThreadPool.ThreadPoolStatistics,
        val counters: Map<String, Long>
    )
}