package com.devcode940.ForexAnalyst.data.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import kotlin.time.measureTime

/**
 * Performance monitoring configuration.
 */
data class PerformanceConfig(
    val slowThresholdMs: Long = 100,      // Operations slower than this are logged as warnings
    val verySlowThresholdMs: Long = 500,  // Operations slower than this are logged as errors
    val enabled: Boolean = true,         // Whether monitoring is enabled
    val logToConsole: Boolean = true,     // Whether to log to console
    val trackHistory: Boolean = true,     // Whether to track execution history
    val historySize: Int = 1000           // Maximum number of executions to track
) {
    companion object {
        val DEFAULT = PerformanceConfig()
        val PRODUCTION = PerformanceConfig(
            slowThresholdMs = 200,
            verySlowThresholdMs = 1000,
            enabled = true,
            logToConsole = false,
            trackHistory = true,
            historySize = 500
        )
        val DEBUG = PerformanceConfig(
            slowThresholdMs = 50,
            verySlowThresholdMs = 200,
            enabled = true,
            logToConsole = true,
            trackHistory = true,
            historySize = 2000
        )
    }
}

/**
 * Execution metrics for a monitored operation.
 */
data class ExecutionMetrics(
    val operationName: String,
    val duration: Duration,
    val durationMs: Long,
    val startTime: Long,
    val endTime: Long,
    val threadName: String,
    val success: Boolean,
    val error: Throwable? = null,
    val tags: Map<String, Any> = emptyMap()
) {
    /**
     * Check if this execution was slow.
     */
    fun isSlow(config: PerformanceConfig): Boolean {
        return durationMs >= config.slowThresholdMs
    }

    /**
     * Check if this execution was very slow.
     */
    fun isVerySlow(config: PerformanceConfig): Boolean {
        return durationMs >= config.verySlowThresholdMs
    }

    /**
     * Get a human-readable summary.
     */
    fun summary(): String {
        return "${operationName}: ${durationMs}ms (${if (success) "OK" else "FAILED"})"
    }
}

/**
 * Aggregated metrics for a specific operation.
 */
data class AggregatedMetrics(
    val operationName: String,
    val totalExecutions: Int = 0,
    val totalTimeMs: Long = 0L,
    val minTimeMs: Long = Long.MAX_VALUE,
    val maxTimeMs: Long = 0L,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastExecutionTime: Long? = null
) {
    /**
     * Calculate average execution time.
     */
    val averageTimeMs: Double get() = if (totalExecutions > 0) totalTimeMs.toDouble() / totalExecutions else 0.0

    /**
     * Calculate success rate.
     */
    val successRate: Double get() = if (totalExecutions > 0) successCount.toDouble() / totalExecutions else 0.0

    /**
     * Check if this operation has issues.
     */
    fun hasIssues(config: PerformanceConfig): Boolean {
        return averageTimeMs >= config.slowThresholdMs || failureCount > 0
    }

    /**
     * Merge with another aggregated metrics.
     */
    fun merge(other: AggregatedMetrics): AggregatedMetrics {
        require(operationName == other.operationName) { "Cannot merge metrics for different operations" }
        
        return AggregatedMetrics(
            operationName = operationName,
            totalExecutions = totalExecutions + other.totalExecutions,
            totalTimeMs = totalTimeMs + other.totalTimeMs,
            minTimeMs = minOf(minTimeMs, other.minTimeMs),
            maxTimeMs = maxOf(maxTimeMs, other.maxTimeMs),
            successCount = successCount + other.successCount,
            failureCount = failureCount + other.failureCount,
            lastExecutionTime = other.lastExecutionTime ?: lastExecutionTime
        )
    }
}

/**
 * Performance monitor for tracking execution metrics.
 * 
 * Features:
 * - Tracks execution time for operations
 * - Aggregates metrics by operation name
 * - Detects slow operations
 * - Provides performance reports
 * - Thread-safe
 */
class PerformanceMonitor(
    private val config: PerformanceConfig = PerformanceConfig.DEFAULT
) {
    companion object {
        private const val TAG = "PerformanceMonitor"
        
        @Volatile
        private var instance: PerformanceMonitor? = null
        
        /**
         * Get the singleton instance.
         */
        fun getInstance(config: PerformanceConfig = PerformanceConfig.DEFAULT): PerformanceMonitor {
            return instance ?: synchronized(this) {
                instance ?: PerformanceMonitor(config).also { instance = it }
            }
        }

        /**
         * Reset the singleton instance.
         */
        fun resetInstance() {
            instance = null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()
    
    // Metrics storage
    private val executionHistory = mutableListOf<ExecutionMetrics>()
    private val aggregatedMetrics = mutableMapOf<String, AggregatedMetrics>()
    
    // Flow for real-time monitoring
    private val _metricsFlow = MutableSharedFlow<ExecutionMetrics>(extraBufferCapacity = 16)
    val metricsFlow = _metricsFlow.asSharedFlow()

    /**
     * Monitor an operation execution.
     * 
     * @param name Name of the operation
     * @param tags Additional tags for categorization
     * @param block The operation to monitor
     * @return The result of the operation
     */
    suspend fun <T> monitor(
        name: String,
        tags: Map<String, Any> = emptyMap(),
        block: suspend () -> T
    ): T {
        if (!config.enabled) {
            return block()
        }

        val threadName = Thread.currentThread().name
        val startTime = System.currentTimeMillis()
        var success = false
        var error: Throwable? = null
        var result: T? = null

        try {
            result = block()
            success = true
        } catch (e: Throwable) {
            error = e
            throw e
        } finally {
            val endTime = System.currentTimeMillis()
            val duration = (endTime - startTime).milliseconds
            
            val metrics = ExecutionMetrics(
                operationName = name,
                duration = duration,
                durationMs = endTime - startTime,
                startTime = startTime,
                endTime = endTime,
                threadName = threadName,
                success = success,
                error = error,
                tags = tags
            )

            // Track metrics
            trackMetrics(metrics)
        }

        return result!!
    }

    /**
     * Monitor an operation and return a result with metrics.
     * 
     * @param name Name of the operation
     * @param tags Additional tags for categorization
     * @param block The operation to monitor
     * @return Pair of result and metrics
     */
    suspend fun <T> monitorWithMetrics(
        name: String,
        tags: Map<String, Any> = emptyMap(),
        block: suspend () -> T
    ): Pair<T, ExecutionMetrics> {
        if (!config.enabled) {
            val startTime = System.currentTimeMillis()
            val result = block()
            val endTime = System.currentTimeMillis()
            
            return Pair(
                result,
                ExecutionMetrics(
                    operationName = name,
                    duration = (endTime - startTime).milliseconds,
                    durationMs = endTime - startTime,
                    startTime = startTime,
                    endTime = endTime,
                    threadName = Thread.currentThread().name,
                    success = true,
                    tags = tags
                )
            )
        }

        val threadName = Thread.currentThread().name
        val startTime = System.currentTimeMillis()
        var success = false
        var error: Throwable? = null
        var result: T? = null

        try {
            result = block()
            success = true
        } catch (e: Throwable) {
            error = e
            throw e
        } finally {
            val endTime = System.currentTimeMillis()
            val duration = (endTime - startTime).milliseconds
            
            val metrics = ExecutionMetrics(
                operationName = name,
                duration = duration,
                durationMs = endTime - startTime,
                startTime = startTime,
                endTime = endTime,
                threadName = threadName,
                success = success,
                error = error,
                tags = tags
            )

            // Track metrics
            trackMetrics(metrics)
            
            return Pair(result!!, metrics)
        }
    }

    /**
     * Track metrics for an execution.
     */
    private suspend fun trackMetrics(metrics: ExecutionMetrics) {
        mutex.withLock {
            // Add to history
            if (config.trackHistory) {
                executionHistory.add(metrics)
                if (executionHistory.size > config.historySize) {
                    executionHistory.removeAt(0)
                }
            }

            // Update aggregated metrics
            val current = aggregatedMetrics[metrics.operationName] ?: AggregatedMetrics(
                operationName = metrics.operationName
            )
            
            aggregatedMetrics[metrics.operationName] = current.copy(
                totalExecutions = current.totalExecutions + 1,
                totalTimeMs = current.totalTimeMs + metrics.durationMs,
                minTimeMs = minOf(current.minTimeMs, metrics.durationMs),
                maxTimeMs = maxOf(current.maxTimeMs, metrics.durationMs),
                successCount = if (metrics.success) current.successCount + 1 else current.successCount,
                failureCount = if (!metrics.success) current.failureCount + 1 else current.failureCount,
                lastExecutionTime = metrics.endTime
            )

            // Log slow operations
            if (config.logToConsole) {
                when {
                    metrics.isVerySlow(config) -> {
                        Log.e(TAG, "VERY SLOW: ${metrics.summary()} on ${metrics.threadName}")
                    }
                    metrics.isSlow(config) -> {
                        Log.w(TAG, "SLOW: ${metrics.summary()} on ${metrics.threadName}")
                    }
                    metrics.error != null -> {
                        Log.e(TAG, "FAILED: ${metrics.summary()} on ${metrics.threadName}", metrics.error)
                    }
                }
            }

            // Emit to flow
            scope.launch {
                _metricsFlow.emit(metrics)
            }
        }
    }

    /**
     * Get execution history.
     */
    suspend fun getExecutionHistory(): List<ExecutionMetrics> {
        return mutex.withLock { executionHistory.toList() }
    }

    /**
     * Get aggregated metrics for all operations.
     */
    suspend fun getAggregatedMetrics(): Map<String, AggregatedMetrics> {
        return mutex.withLock { aggregatedMetrics.toMap() }
    }

    /**
     * Get aggregated metrics for a specific operation.
     */
    suspend fun getMetricsForOperation(name: String): AggregatedMetrics? {
        return mutex.withLock { aggregatedMetrics[name] }
    }

    /**
     * Get the slowest operations.
     */
    suspend fun getSlowestOperations(limit: Int = 10): List<AggregatedMetrics> {
        return mutex.withLock {
            aggregatedMetrics.values
                .sortedByDescending { it.averageTimeMs }
                .take(limit)
        }
    }

    /**
     * Get operations with the most failures.
     */
    suspend fun getMostFailingOperations(limit: Int = 10): List<AggregatedMetrics> {
        return mutex.withLock {
            aggregatedMetrics.values
                .filter { it.failureCount > 0 }
                .sortedByDescending { it.failureCount }
                .take(limit)
        }
    }

    /**
     * Get operations with the highest execution count.
     */
    suspend fun getMostExecutedOperations(limit: Int = 10): List<AggregatedMetrics> {
        return mutex.withLock {
            aggregatedMetrics.values
                .sortedByDescending { it.totalExecutions }
                .take(limit)
        }
    }

    /**
     * Clear all tracked metrics.
     */
    suspend fun clear() {
        mutex.withLock {
            executionHistory.clear()
            aggregatedMetrics.clear()
        }
    }

    /**
     * Generate a performance report.
     */
    suspend fun generateReport(): String {
        val metrics = getAggregatedMetrics()
        val slowest = getSlowestOperations(5)
        val mostFailing = getMostFailingOperations(5)
        
        return buildString {
            appendLine("=== PERFORMANCE REPORT ===")
            appendLine()
            
            appendLine("Summary:")
            appendLine("  Total operations tracked: ${metrics.values.sumOf { it.totalExecutions }}")
            appendLine("  Total unique operations: ${metrics.size}")
            appendLine("  Total failures: ${metrics.values.sumOf { it.failureCount }}")
            appendLine("  Overall success rate: ${if (metrics.isNotEmpty()) "${metrics.values.sumOf { it.successCount } / metrics.values.sumOf { it.totalExecutions }.toDouble() * 100}%" else "N/A"}")
            appendLine()
            
            if (slowest.isNotEmpty()) {
                appendLine("Top 5 Slowest Operations:")
                slowest.forEach { metric ->
                    appendLine("  - ${metric.operationName}: avg=${"%.2f".format(metric.averageTimeMs)}ms, min=${metric.minTimeMs}ms, max=${metric.maxTimeMs}ms, count=${metric.totalExecutions}")
                }
                appendLine()
            }
            
            if (mostFailing.isNotEmpty()) {
                appendLine("Top 5 Most Failing Operations:")
                mostFailing.forEach { metric ->
                    appendLine("  - ${metric.operationName}: failures=${metric.failureCount}/${metric.totalExecutions} (${"%.2f".format(metric.successRate * 100)}% success)")
                }
                appendLine()
            }
            
            appendLine("=== END REPORT ===")
        }
    }

    /**
     * Create a timed block for simple monitoring.
     */
    inline fun <T> timed(
        name: String,
        block: () -> T
    ): T {
        if (!config.enabled) {
            return block()
        }

        val startTime = System.nanoTime()
        val result = block()
        val durationNs = System.nanoTime() - startTime
        val durationMs = durationNs.toDouble() / 1_000_000
        
        if (config.logToConsole && durationMs >= config.slowThresholdMs) {
            Log.w(TAG, "SLOW (sync): $name: ${"%.2f".format(durationMs)}ms")
        }
        
        return result
    }

    /**
     * Measure the execution time of a block.
     */
    inline fun measureTime(
        name: String,
        block: () -> Unit
    ): Duration {
        if (!config.enabled) {
            block()
            return Duration.ZERO
        }

        return measureTime {
            block()
        }.also { duration ->
            if (config.logToConsole && duration.toLong(DurationUnit.MILLISECONDS) >= config.slowThresholdMs) {
                Log.w(TAG, "SLOW (sync): $name: ${duration.toLong(DurationUnit.MILLISECONDS)}ms")
            }
        }
    }
}

/**
 * Extension function to monitor a suspend function.
 */
suspend fun <T> PerformanceMonitor.monitorSuspend(
    name: String,
    tags: Map<String, Any> = emptyMap(),
    block: suspend () -> T
): T = this.monitor(name, tags, block)

/**
 * Extension function to monitor a suspend function with metrics.
 */
suspend fun <T> PerformanceMonitor.monitorSuspendWithMetrics(
    name: String,
    tags: Map<String, Any> = emptyMap(),
    block: suspend () -> T
): Pair<T, ExecutionMetrics> = this.monitorWithMetrics(name, tags, block)

/**
 * Create a performance monitor with default configuration.
 */
fun createPerformanceMonitor(config: PerformanceConfig = PerformanceConfig.DEFAULT): PerformanceMonitor {
    return PerformanceMonitor(config)
}
