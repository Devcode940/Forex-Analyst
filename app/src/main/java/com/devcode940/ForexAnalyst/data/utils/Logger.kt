package com.devcode940.ForexAnalyst.data.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-ready comprehensive logging utility.
 * 
 * Features:
 * - Structured logging with tags and levels
 * - Performance metrics tracking
 * - Error rate monitoring
 * - Thread-safe operations
 * - Configurable log levels
 * - Log filtering and sampling
 * 
 * Usage:
 * ```kotlin
 * // Basic logging
 * Logger.d("Debug message")
 * Logger.i("Info message")
 * Logger.w("Warning message")
 * Logger.e("Error message", exception)
 * 
 * // Tagged logging
 * Logger.d("Debug message", tag = "Network")
 * 
 * // Performance tracking
 * val startTime = System.nanoTime()
 * // ... operation ...
 * Logger.perf("Operation completed", System.nanoTime() - startTime)
 * 
 * // Conditional logging
 * Logger.d("Debug message", enabled = BuildConfig.DEBUG)
 * 
 * // With context
 * Logger.i("User logged in", context = mapOf("userId" to user.id))
 * ```
 */
object Logger {
    
    // ========================================================================
    // CONFIGURATION
    // ========================================================================
    
    /**
     * Log level enumeration.
     */
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        ASSERT
    }
    
    /**
     * Current minimum log level.
     */
    var minLogLevel: Level = Level.INFO
    
    /**
     * Whether performance logging is enabled.
     */
    var isPerformanceLoggingEnabled: Boolean = true
    
    /**
     * Whether to include timestamps in logs.
     */
    var includeTimestamps: Boolean = true
    
    /**
     * Whether to include thread info in logs.
     */
    var includeThreadInfo: Boolean = true
    
    /**
     * Maximum length for log messages (to prevent truncation).
     */
    const val MAX_MESSAGE_LENGTH = 4000
    
    // ========================================================================
    // METRICS TRACKING
    // ========================================================================
    
    private val errorCounts = ConcurrentHashMap<String, AtomicLong>()
    private val warningCounts = ConcurrentHashMap<String, AtomicLong>()
    private val performanceMetrics = ConcurrentHashMap<String, PerformanceMetrics>()
    
    /**
     * Performance metrics for a specific operation.
     */
    data class PerformanceMetrics(
        val count: AtomicLong = AtomicLong(0),
        val totalTimeNanos: AtomicLong = AtomicLong(0),
        val minTimeNanos: AtomicLong = AtomicLong(Long.MAX_VALUE),
        val maxTimeNanos: AtomicLong = AtomicLong(0)
    ) {
        fun getAverageTimeMs(): Double {
            val count = this.count.get()
            if (count == 0L) return 0.0
            return (totalTimeNanos.get().toDouble() / count) / 1_000_000.0
        }
        
        fun getMinTimeMs(): Double = minTimeNanos.get().toDouble() / 1_000_000.0
        fun getMaxTimeMs(): Double = maxTimeNanos.get().toDouble() / 1_000_000.0
    }
    
    // ========================================================================
    // LOGGING FUNCTIONS
    // ========================================================================
    
    /**
     * Log a verbose message.
     */
    fun v(
        message: String,
        throwable: Throwable? = null,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null,
        enabled: Boolean = true
    ) {
        if (!enabled || minLogLevel.ordinal > Level.VERBOSE.ordinal) return
        log(Level.VERBOSE, message, throwable, tag, context)
    }
    
    /**
     * Log a debug message.
     */
    fun d(
        message: String,
        throwable: Throwable? = null,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null,
        enabled: Boolean = true
    ) {
        if (!enabled || minLogLevel.ordinal > Level.DEBUG.ordinal) return
        log(Level.DEBUG, message, throwable, tag, context)
    }
    
    /**
     * Log an info message.
     */
    fun i(
        message: String,
        throwable: Throwable? = null,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null,
        enabled: Boolean = true
    ) {
        if (!enabled || minLogLevel.ordinal > Level.INFO.ordinal) return
        log(Level.INFO, message, throwable, tag, context)
    }
    
    /**
     * Log a warning message.
     */
    fun w(
        message: String,
        throwable: Throwable? = null,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null,
        enabled: Boolean = true
    ) {
        if (!enabled || minLogLevel.ordinal > Level.WARN.ordinal) return
        log(Level.WARN, message, throwable, tag, context)
        
        // Track warning counts
        if (tag.isNotBlank()) {
            warningCounts.getOrPut(tag) { AtomicLong(0) }.incrementAndGet()
        }
    }
    
    /**
     * Log an error message.
     */
    fun e(
        message: String,
        throwable: Throwable? = null,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null,
        enabled: Boolean = true
    ) {
        if (!enabled || minLogLevel.ordinal > Level.ERROR.ordinal) return
        log(Level.ERROR, message, throwable, tag, context)
        
        // Track error counts
        if (tag.isNotBlank()) {
            errorCounts.getOrPut(tag) { AtomicLong(0) }.incrementAndGet()
        }
    }
    
    /**
     * Log a performance metric.
     */
    fun perf(
        message: String,
        durationNanos: Long,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null,
        enabled: Boolean = true
    ) {
        if (!enabled || !isPerformanceLoggingEnabled) return
        
        val durationMs = durationNanos.toDouble() / 1_000_000.0
        val formattedMessage = "$message in ${String.format("%.3f", durationMs)}ms"
        
        // Use info level for performance logs
        if (minLogLevel.ordinal <= Level.INFO.ordinal) {
            log(Level.INFO, formattedMessage, null, tag, context)
        }
        
        // Track performance metrics
        if (tag.isNotBlank()) {
            val metrics = performanceMetrics.getOrPut(tag) { PerformanceMetrics() }
            metrics.count.incrementAndGet()
            metrics.totalTimeNanos.addAndGet(durationNanos)
            
            // Update min/max
            val currentMin = metrics.minTimeNanos.get()
            if (durationNanos < currentMin) {
                metrics.minTimeNanos.set(durationNanos)
            }
            
            val currentMax = metrics.maxTimeNanos.get()
            if (durationNanos > currentMax) {
                metrics.maxTimeNanos.set(durationNanos)
            }
        }
    }
    
    /**
     * Log an assertion failure.
     */
    fun wtf(
        message: String,
        throwable: Throwable? = null,
        tag: String = "ForexApp",
        context: Map<String, Any>? = null
    ) {
        if (minLogLevel.ordinal > Level.ASSERT.ordinal) return
        log(Level.ASSERT, message, throwable, tag, context)
    }
    
    // ========================================================================
    // CORE LOGGING FUNCTION
    // ========================================================================
    
    /**
     * Core logging function that handles all log levels.
     */
    private fun log(
        level: Level,
        message: String,
        throwable: Throwable?,
        tag: String,
        context: Map<String, Any>?
    ) {
        // Truncate message if too long
        val truncatedMessage = if (message.length > MAX_MESSAGE_LENGTH) {
            message.take(MAX_MESSAGE_LENGTH) + "... [TRUNCATED]"
        } else {
            message
        }
        
        // Build the full log message
        val fullMessage = buildString {
            // Add timestamp if enabled
            if (includeTimestamps) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(System.currentTimeMillis())
                append("[$timestamp] ")
            }
            
            // Add thread info if enabled
            if (includeThreadInfo) {
                val threadName = Thread.currentThread().name
                append("[$threadName] ")
            }
            
            // Add tag
            append("[$tag] ")
            
            // Add message
            append(truncatedMessage)
            
            // Add context if provided
            if (context != null && context.isNotEmpty()) {
                append(" ")
                append(context.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
        }
        
        // Log using Android's Log class
        when (level) {
            Level.VERBOSE -> {
                if (throwable != null) Log.v(tag, fullMessage, throwable)
                else Log.v(tag, fullMessage)
            }
            Level.DEBUG -> {
                if (throwable != null) Log.d(tag, fullMessage, throwable)
                else Log.d(tag, fullMessage)
            }
            Level.INFO -> {
                if (throwable != null) Log.i(tag, fullMessage, throwable)
                else Log.i(tag, fullMessage)
            }
            Level.WARN -> {
                if (throwable != null) Log.w(tag, fullMessage, throwable)
                else Log.w(tag, fullMessage)
            }
            Level.ERROR -> {
                if (throwable != null) Log.e(tag, fullMessage, throwable)
                else Log.e(tag, fullMessage)
            }
            Level.ASSERT -> {
                if (throwable != null) Log.wtf(tag, fullMessage, throwable)
                else Log.wtf(tag, fullMessage)
            }
        }
    }
    
    // ========================================================================
    // METRICS FUNCTIONS
    // ========================================================================
    
    /**
     * Get error count for a specific tag.
     */
    fun getErrorCount(tag: String): Long {
        return errorCounts[tag]?.get() ?: 0L
    }
    
    /**
     * Get warning count for a specific tag.
     */
    fun getWarningCount(tag: String): Long {
        return warningCounts[tag]?.get() ?: 0L
    }
    
    /**
     * Get performance metrics for a specific tag.
     */
    fun getPerformanceMetrics(tag: String): PerformanceMetrics? {
        return performanceMetrics[tag]
    }
    
    /**
     * Get all error counts.
     */
    fun getAllErrorCounts(): Map<String, Long> {
        return errorCounts.mapValues { it.value.get() }
    }
    
    /**
     * Get all warning counts.
     */
    fun getAllWarningCounts(): Map<String, Long> {
        return warningCounts.mapValues { it.value.get() }
    }
    
    /**
     * Get all performance metrics.
     */
    fun getAllPerformanceMetrics(): Map<String, PerformanceMetrics> {
        return performanceMetrics
    }
    
    /**
     * Reset all metrics.
     */
    fun resetMetrics() {
        errorCounts.clear()
        warningCounts.clear()
        performanceMetrics.clear()
    }
    
    /**
     * Get a summary of all metrics.
     */
    fun getMetricsSummary(): MetricsSummary {
        val totalErrors = errorCounts.values.sumOf { it.get() }
        val totalWarnings = warningCounts.values.sumOf { it.get() }
        
        val slowestOperations = performanceMetrics.map { (tag, metrics) ->
            tag to metrics.getMaxTimeMs()
        }.toList().sortedByDescending { it.second }.take(5)
        
        val mostCalledOperations = performanceMetrics.map { (tag, metrics) ->
            tag to metrics.count.get()
        }.toList().sortedByDescending { it.second }.take(5)
        
        return MetricsSummary(
            totalErrors = totalErrors,
            totalWarnings = totalWarnings,
            errorByTag = errorCounts.mapValues { it.value.get() },
            warningByTag = warningCounts.mapValues { it.value.get() },
            slowestOperations = slowestOperations.toMap(),
            mostCalledOperations = mostCalledOperations.toMap()
        )
    }
    
    /**
     * Summary of all logging metrics.
     */
    data class MetricsSummary(
        val totalErrors: Long,
        val totalWarnings: Long,
        val errorByTag: Map<String, Long>,
        val warningByTag: Map<String, Long>,
        val slowestOperations: Map<String, Double>,
        val mostCalledOperations: Map<String, Long>
    )
    
    // ========================================================================
    // UTILITY FUNCTIONS
    // ========================================================================
    
    /**
     * Create a tagged logger for a specific component.
     */
    fun forTag(tag: String): TaggedLogger {
        return TaggedLogger(tag)
    }
    
    /**
     * Tagged logger for a specific component.
     */
    class TaggedLogger(private val tag: String) {
        fun v(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) {
            Logger.v(message, throwable, tag, context)
        }
        
        fun d(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) {
            Logger.d(message, throwable, tag, context)
        }
        
        fun i(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) {
            Logger.i(message, throwable, tag, context)
        }
        
        fun w(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) {
            Logger.w(message, throwable, tag, context)
        }
        
        fun e(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) {
            Logger.e(message, throwable, tag, context)
        }
        
        fun perf(message: String, durationNanos: Long, context: Map<String, Any>? = null) {
            Logger.perf(message, durationNanos, tag, context)
        }
        
        fun wtf(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) {
            Logger.wtf(message, throwable, tag, context)
        }
    }
}

// ========================================================================
// EXTENSION FUNCTIONS
// ========================================================================

/**
 * Log debug message with this object as context.
 */
fun Any.logDebug(message: String, tag: String = this::class.java.simpleName) {
    Logger.d(message, tag = tag, context = mapOf("object" to this::class.java.simpleName))
}

/**
 * Log info message with this object as context.
 */
fun Any.logInfo(message: String, tag: String = this::class.java.simpleName) {
    Logger.i(message, tag = tag, context = mapOf("object" to this::class.java.simpleName))
}

/**
 * Log warning message with this object as context.
 */
fun Any.logWarn(message: String, tag: String = this::class.java.simpleName) {
    Logger.w(message, tag = tag, context = mapOf("object" to this::class.java.simpleName))
}

/**
 * Log error message with this object as context.
 */
fun Any.logError(message: String, throwable: Throwable? = null, tag: String = this::class.java.simpleName) {
    Logger.e(message, throwable, tag = tag, context = mapOf("object" to this::class.java.simpleName))
}

/**
 * Measure execution time and log performance.
 */
suspend fun <T> measureTime(
    tag: String,
    operation: suspend () -> T
): T {
    val startTime = System.nanoTime()
    try {
        return operation()
    } finally {
        val duration = System.nanoTime() - startTime
        Logger.perf("Operation completed", duration, tag)
    }
}

/**
 * Measure execution time and log with result.
 */
suspend fun <T> measureTimeWithResult(
    tag: String,
    operation: suspend () -> T
): T {
    val startTime = System.nanoTime()
    try {
        val result = operation()
        val duration = System.nanoTime() - startTime
        Logger.perf("Operation completed with result", duration, tag, 
            context = mapOf("result" to result?.toString() ?: "null"))
        return result
    } catch (e: Exception) {
        val duration = System.nanoTime() - startTime
        Logger.e("Operation failed", e, tag, 
            context = mapOf("durationMs" to duration.toDouble() / 1_000_000.0))
        throw e
    }
}
