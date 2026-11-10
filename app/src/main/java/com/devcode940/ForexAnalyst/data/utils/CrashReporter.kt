package com.devcode940.ForexAnalyst.data.utils

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-ready Crash Reporter using Firebase Crashlytics.
 * 
 * Features:
 * - Automatic crash reporting
 * - Non-fatal exception reporting
 * - Custom keys and attributes
 * - Breadcrumbs for debugging
 * - Error rate monitoring
 * - Thread-safe operations
 * 
 * Usage:
 * ```kotlin
 * // Initialize in Application class
 * CrashReporter.initialize(context)
 * 
 * // Report exceptions
 * try {
 *     riskyOperation()
 * } catch (e: Exception) {
 *     CrashReporter.report(e)
 *     throw e
 * }
 * 
 * // Add custom keys
 * CrashReporter.setCustomKey("user_id", user.id)
 * 
 * // Add breadcrumbs
 * CrashReporter.addBreadcrumb("User logged in")
 * 
 * // Record custom events
 * CrashReporter.recordEvent("api_call_failed", mapOf("endpoint" to "/users"))
 * ```
 */
object CrashReporter {
    
    private const val TAG = "CrashReporter"
    
    // ========================================================================
    // INITIALIZATION
    // ========================================================================
    
    private var isInitialized = false
    private var crashlytics: FirebaseCrashlytics? = null
    
    /**
     * Initialize the crash reporter.
     * Should be called from Application.onCreate()
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics?.setCrashlyticsCollectionEnabled(true)
            isInitialized = true
            Log.i(TAG, "CrashReporter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CrashReporter", e)
            // Fallback to logging if Crashlytics is not available
            isInitialized = false
        }
    }
    
    /**
     * Check if CrashReporter is initialized and available.
     */
    fun isAvailable(): Boolean = isInitialized && crashlytics != null
    
    // ========================================================================
    // EXCEPTION REPORTING
    // ========================================================================
    
    /**
     * Report a non-fatal exception.
     * 
     * @param throwable The exception to report
     * @param message Optional custom message
     * @param customKeys Optional custom keys to include
     */
    fun report(
        throwable: Throwable,
        message: String? = null,
        customKeys: Map<String, Any>? = null
    ) {
        if (!isAvailable()) {
            Log.e(TAG, "CrashReporter not available: ${throwable.message}", throwable)
            return
        }
        
        try {
            // Add custom message if provided
            message?.let { crashlytics?.log(it) }
            
            // Add custom keys if provided
            customKeys?.forEach { (key, value) ->
                crashlytics?.setCustomKey(key, value.toString())
            }
            
            // Record the exception
            crashlytics?.recordException(throwable)
            
            Log.d(TAG, "Reported exception: ${throwable.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report exception", e)
        }
    }
    
    /**
     * Report a non-fatal exception with a specific tag.
     */
    fun reportWithTag(
        throwable: Throwable,
        tag: String,
        message: String? = null,
        customKeys: Map<String, Any>? = null
    ) {
        // Add tag to custom keys
        val keysWithTag = (customKeys ?: emptyMap()).toMutableMap().apply {
            put("report_tag", tag)
        }
        
        report(throwable, message, keysWithTag)
    }
    
    // ========================================================================
    // CUSTOM KEYS
    // ========================================================================
    
    /**
     * Set a custom key for crash reports.
     * These keys will be included in all future crash reports.
     */
    fun setCustomKey(key: String, value: Any) {
        if (!isAvailable()) {
            Log.d(TAG, "Cannot set custom key - CrashReporter not available")
            return
        }
        
        try {
            crashlytics?.setCustomKey(key, value.toString())
            Log.d(TAG, "Set custom key: $key = $value")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set custom key", e)
        }
    }
    
    /**
     * Set multiple custom keys at once.
     */
    fun setCustomKeys(keys: Map<String, Any>) {
        keys.forEach { (key, value) -> setCustomKey(key, value) }
    }
    
    /**
     * Remove a custom key.
     */
    fun removeCustomKey(key: String) {
        if (!isAvailable()) return
        
        try {
            crashlytics?.setCustomKey(key, null)
            Log.d(TAG, "Removed custom key: $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove custom key", e)
        }
    }
    
    // ========================================================================
    // BREADCRUMBS
    // ========================================================================
    
    /**
     * Add a breadcrumb for debugging.
     * Breadcrumbs help trace the sequence of events leading to a crash.
     */
    fun addBreadcrumb(message: String, type: BreadcrumbType = BreadcrumbType.LOG) {
        if (!isAvailable()) {
            Log.d(TAG, "Breadcrumb: $message")
            return
        }
        
        try {
            when (type) {
                BreadcrumbType.LOG -> crashlytics?.log(message)
                BreadcrumbType.DEBUG -> crashlytics?.log("DEBUG: $message")
                BreadcrumbType.INFO -> crashlytics?.log("INFO: $message")
                BreadcrumbType.WARNING -> crashlytics?.log("WARN: $message")
                BreadcrumbType.ERROR -> crashlytics?.log("ERROR: $message")
            }
            Log.d(TAG, "Added breadcrumb: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add breadcrumb", e)
        }
    }
    
    /**
     * Breadcrumb types for categorization.
     */
    enum class BreadcrumbType {
        LOG,
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }
    
    // ========================================================================
    // CUSTOM EVENTS
    // ========================================================================
    
    /**
     * Record a custom event.
     * 
     * @param name Event name
     * @param attributes Optional event attributes
     */
    fun recordEvent(name: String, attributes: Map<String, Any>? = null) {
        if (!isAvailable()) {
            Log.d(TAG, "Event: $name")
            return
        }
        
        try {
            attributes?.forEach { (key, value) ->
                crashlytics?.setCustomKey("event_$name/$key", value.toString())
            }
            
            crashlytics?.log("EVENT: $name")
            Log.d(TAG, "Recorded event: $name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record event", e)
        }
    }
    
    // ========================================================================
    // ERROR METRICS
    // ========================================================================
    
    private val errorCounts = ConcurrentHashMap<String, AtomicLong>()
    private val lastErrorTimes = ConcurrentHashMap<String, Long>()
    
    /**
     * Record an error with optional classification.
     */
    fun recordError(
        errorType: String,
        message: String? = null,
        throwable: Throwable? = null
    ) {
        // Update error count
        errorCounts.getOrPut(errorType) { AtomicLong(0) }.incrementAndGet()
        lastErrorTimes[errorType] = System.currentTimeMillis()
        
        // Report to Crashlytics if available
        if (isAvailable() && throwable != null) {
            report(throwable, message, mapOf("error_type" to errorType))
        } else if (throwable != null) {
            Log.e(TAG, "Error ($errorType): ${throwable.message}", throwable)
        } else if (message != null) {
            Log.e(TAG, "Error ($errorType): $message")
        }
    }
    
    /**
     * Get error count for a specific error type.
     */
    fun getErrorCount(errorType: String): Long {
        return errorCounts[errorType]?.get() ?: 0L
    }
    
    /**
     * Get time of last error for a specific error type.
     */
    fun getLastErrorTime(errorType: String): Long? {
        return lastErrorTimes[errorType]
    }
    
    /**
     * Get all error statistics.
     */
    fun getErrorStats(): Map<String, ErrorStats> {
        return errorCounts.mapValues { (errorType, count) ->
            ErrorStats(
                errorType = errorType,
                count = count.get(),
                lastOccurrence = lastErrorTimes[errorType]
            )
        }
    }
    
    /**
     * Error statistics for monitoring.
     */
    data class ErrorStats(
        val errorType: String,
        val count: Long,
        val lastOccurrence: Long?
    )
    
    // ========================================================================
    // UTILITY FUNCTIONS
    // ========================================================================
    
    /**
     * Execute a function with automatic error reporting.
     */
    inline fun <T> executeWithReport(
        errorType: String,
        operation: () -> T
    ): T {
        return try {
            operation()
        } catch (e: Exception) {
            recordError(errorType, e.message, e)
            throw e
        }
    }
    
    /**
     * Execute a suspend function with automatic error reporting.
     */
    suspend inline fun <T> executeWithReportSuspend(
        errorType: String,
        operation: suspend () -> T
    ): T {
        return try {
            operation()
        } catch (e: Exception) {
            recordError(errorType, e.message, e)
            throw e
        }
    }
    
    /**
     * Wrap a function to catch and report all exceptions.
     * Returns null on failure.
     */
    inline fun <T> tryWithReport(
        errorType: String,
        operation: () -> T
    ): T? {
        return try {
            operation()
        } catch (e: Exception) {
            recordError(errorType, e.message, e)
            null
        }
    }
    
    /**
     * Wrap a suspend function to catch and report all exceptions.
     * Returns null on failure.
     */
    suspend inline fun <T> tryWithReportSuspend(
        errorType: String,
        operation: suspend () -> T
    ): T? {
        return try {
            operation()
        } catch (e: Exception) {
            recordError(errorType, e.message, e)
            null
        }
    }
    
    // ========================================================================
    // CLEANUP
    // ========================================================================
    
    /**
     * Reset all error statistics.
     */
    fun resetStats() {
        errorCounts.clear()
        lastErrorTimes.clear()
        Log.i(TAG, "Error statistics reset")
    }
    
    /**
     * Clear all custom keys.
     */
    fun clearCustomKeys() {
        if (!isAvailable()) return
        
        try {
            // This is a workaround since Crashlytics doesn't have a clearAllKeys method
            // We can't actually clear all keys, but we can set them to null
            // In practice, you'd need to know all the keys you've set
            Log.i(TAG, "Custom keys cannot be cleared - use removeCustomKey for specific keys")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear custom keys", e)
        }
    }
}

// ========================================================================
// EXTENSION FUNCTIONS
// ========================================================================

/**
 * Report this exception to CrashReporter.
 */
fun Throwable.reportToCrashlytics(
    message: String? = null,
    customKeys: Map<String, Any>? = null
) {
    CrashReporter.report(this, message, customKeys)
}

/**
 * Report this exception with a tag.
 */
fun Throwable.reportToCrashlyticsWithTag(
    tag: String,
    message: String? = null,
    customKeys: Map<String, Any>? = null
) {
    CrashReporter.reportWithTag(this, tag, message, customKeys)
}

/**
 * Execute this function with automatic error reporting.
 */
inline fun <T> withCrashReport(
    errorType: String,
    operation: () -> T
): T {
    return CrashReporter.executeWithReport(errorType, operation)
}

/**
 * Execute this suspend function with automatic error reporting.
 */
suspend inline fun <T> withCrashReportSuspend(
    errorType: String,
    operation: suspend () -> T
): T {
    return CrashReporter.executeWithReportSuspend(errorType, operation)
}
