package com.spruceid.mobile.sdk.ble

import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Centralized thread pool management for BLE operations
 *
 * Provides optimized thread pools for different BLE operation types:
 * - IO Operations: File I/O, network, L2CAP socket operations
 * - CPU Operations: CBOR parsing, cryptographic operations
 * - Timer Operations: Timeouts, delays, retry scheduling
 *
 * Replaces ad-hoc thread creation with managed pools to improve:
 * - Resource utilization
 * - Performance predictability
 * - Memory management
 * - Thread lifecycle management
 */
class BleThreadPool private constructor(
    private val config: BleConfiguration = BleConfiguration()
) {

    companion object {
        @Volatile
        private var INSTANCE: BleThreadPool? = null

        fun getInstance(config: BleConfiguration = BleConfiguration()): BleThreadPool {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleThreadPool(config).also { INSTANCE = it }
            }
        }

        /**
         * Reset instance for testing
         */
        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.shutdown()
                INSTANCE = null
            }
        }
    }

    private val logger = BleLogger.getInstance("BleThreadPool", config)

    // Core thread pools for different operation types
    private val ioExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        2, // Core pool size
        config.maxThreadPoolSize,
        config.threadKeepAliveTimeMs,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(100), // Bounded queue to prevent memory issues
        { runnable ->
            Thread(runnable, "BLE-IO-${Thread.currentThread().id}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy() // Back-pressure handling
    )

    private val cpuExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, // Single thread for CPU-intensive operations
        2, // Max 2 threads for CPU operations
        config.threadKeepAliveTimeMs,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(50),
        { runnable ->
            Thread(runnable, "BLE-CPU-${Thread.currentThread().id}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1 // Lower priority for CPU work
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val timerExecutor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(
        2 // Core pool size for timers
    ) { runnable ->
        Thread(runnable, "BLE-Timer-${Thread.currentThread().id}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1 // Higher priority for timers
        }
    }.apply {
        removeOnCancelPolicy = true // Clean up cancelled tasks
    }

    // Coroutine dispatchers wrapping the thread pools
    val ioDispatcher: CoroutineDispatcher = ioExecutor.asCoroutineDispatcher()
    val cpuDispatcher: CoroutineDispatcher = cpuExecutor.asCoroutineDispatcher()
    val timerDispatcher: CoroutineDispatcher = timerExecutor.asCoroutineDispatcher()

    // Coroutine scopes for different operation types
    val ioScope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())
    val cpuScope: CoroutineScope = CoroutineScope(cpuDispatcher + SupervisorJob())
    val timerScope: CoroutineScope = CoroutineScope(timerDispatcher + SupervisorJob())

    init {
        logger.i("BLE thread pool initialized with ${config.maxThreadPoolSize} max threads")
    }

    /**
     * Execute I/O operation (socket, file operations, GATT operations)
     */
    fun executeIOTask(operation: () -> Unit): Future<*> {
        return ioExecutor.submit(operation)
    }

    /**
     * Execute I/O operation with result
     */
    fun <T> executeIOWithResult(operation: () -> T): Future<T> {
        return ioExecutor.submit(Callable(operation))
    }

    /**
     * Execute CPU-intensive operation (crypto, parsing, compression)
     */
    fun executeCPUTask(operation: () -> Unit): Future<*> {
        return cpuExecutor.submit(operation)
    }

    /**
     * Execute CPU operation with result
     */
    fun <T> executeCPUWithResult(operation: () -> T): Future<T> {
        return cpuExecutor.submit(Callable(operation))
    }

    /**
     * Schedule a delayed operation
     */
    fun scheduleDelayed(
        delayMs: Long,
        operation: () -> Unit
    ): ScheduledFuture<*> {
        return timerExecutor.schedule(operation, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Schedule a repeating operation
     */
    fun scheduleRepeating(
        initialDelayMs: Long,
        periodMs: Long,
        operation: () -> Unit
    ): ScheduledFuture<*> {
        return timerExecutor.scheduleWithFixedDelay(
            operation,
            initialDelayMs,
            periodMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Execute coroutine in IO scope
     */
    fun launchIO(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return ioScope.launch(context, start, block)
    }

    /**
     * Execute coroutine in CPU scope
     */
    fun launchCPU(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return cpuScope.launch(context, start, block)
    }

    /**
     * Execute coroutine in Timer scope
     */
    fun launchTimer(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return timerScope.launch(context, start, block)
    }

    /**
     * Execute coroutine with timeout in IO scope
     */
    suspend fun <T> withIOTimeout(
        timeoutMs: Long,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(ioDispatcher) {
        withTimeout(timeoutMs, block)
    }

    /**
     * Execute coroutine with timeout in CPU scope
     */
    suspend fun <T> withCPUTimeout(
        timeoutMs: Long,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(cpuDispatcher) {
        withTimeout(timeoutMs, block)
    }

    /**
     * Get thread pool statistics
     */
    fun getStatistics(): ThreadPoolStatistics {
        return ThreadPoolStatistics(
            ioPoolSize = ioExecutor.poolSize,
            ioActiveThreads = ioExecutor.activeCount,
            ioQueueSize = ioExecutor.queue.size,
            ioCompletedTasks = ioExecutor.completedTaskCount,

            cpuPoolSize = cpuExecutor.poolSize,
            cpuActiveThreads = cpuExecutor.activeCount,
            cpuQueueSize = cpuExecutor.queue.size,
            cpuCompletedTasks = cpuExecutor.completedTaskCount,

            timerPoolSize = timerExecutor.poolSize,
            timerActiveThreads = timerExecutor.activeCount,
            timerQueueSize = timerExecutor.queue.size,
            timerCompletedTasks = timerExecutor.completedTaskCount
        )
    }

    /**
     * Check if thread pools are healthy (not overwhelmed)
     */
    fun isHealthy(): Boolean {
        val stats = getStatistics()

        // Check if queues are not overwhelmed (> 80% capacity)
        val ioQueueHealthy = stats.ioQueueSize < 80
        val cpuQueueHealthy = stats.cpuQueueSize < 40
        val timerQueueHealthy = stats.timerQueueSize < 40

        // Check if we're not using too many threads
        val ioThreadsHealthy = stats.ioActiveThreads <= config.maxThreadPoolSize
        val cpuThreadsHealthy = stats.cpuActiveThreads <= 2
        val timerThreadsHealthy = stats.timerActiveThreads <= 2

        return ioQueueHealthy && cpuQueueHealthy && timerQueueHealthy &&
               ioThreadsHealthy && cpuThreadsHealthy && timerThreadsHealthy
    }

    /**
     * Graceful shutdown of all thread pools
     */
    fun shutdown() {
        logger.i("Shutting down BLE thread pools")

        try {
            // Cancel all coroutine scopes
            ioScope.cancel()
            cpuScope.cancel()
            timerScope.cancel()

            // Shutdown thread pools
            ioExecutor.shutdown()
            cpuExecutor.shutdown()
            timerExecutor.shutdown()

            // Wait for termination with timeout
            val shutdownTimeoutMs = 5000L

            if (!ioExecutor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                logger.w("IO executor did not terminate within timeout, forcing shutdown")
                ioExecutor.shutdownNow()
            }

            if (!cpuExecutor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                logger.w("CPU executor did not terminate within timeout, forcing shutdown")
                cpuExecutor.shutdownNow()
            }

            if (!timerExecutor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                logger.w("Timer executor did not terminate within timeout, forcing shutdown")
                timerExecutor.shutdownNow()
            }

            logger.i("BLE thread pools shut down successfully")

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.w("Thread pool shutdown interrupted", e)

            // Force shutdown if interrupted
            ioExecutor.shutdownNow()
            cpuExecutor.shutdownNow()
            timerExecutor.shutdownNow()
        }
    }

    data class ThreadPoolStatistics(
        val ioPoolSize: Int,
        val ioActiveThreads: Int,
        val ioQueueSize: Int,
        val ioCompletedTasks: Long,

        val cpuPoolSize: Int,
        val cpuActiveThreads: Int,
        val cpuQueueSize: Int,
        val cpuCompletedTasks: Long,

        val timerPoolSize: Int,
        val timerActiveThreads: Int,
        val timerQueueSize: Int,
        val timerCompletedTasks: Long
    ) {
        val totalPoolSize: Int = ioPoolSize + cpuPoolSize + timerPoolSize
        val totalActiveThreads: Int = ioActiveThreads + cpuActiveThreads + timerActiveThreads
        val totalQueueSize: Int = ioQueueSize + cpuQueueSize + timerQueueSize
        val totalCompletedTasks: Long = ioCompletedTasks + cpuCompletedTasks + timerCompletedTasks
    }
}