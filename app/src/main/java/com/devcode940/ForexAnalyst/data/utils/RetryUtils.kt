package com.devcode940.ForexAnalyst.data.utils

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Production-ready Retry utilities with exponential backoff.
 * 
 * Features:
 * - Configurable max attempts and delays
 * - Exponential backoff with jitter
 * - Predicate-based retry conditions
 * - Comprehensive logging
 * - Coroutine-friendly suspend functions
 * 
 * Usage:
 * ```kotlin
 * // Simple retry
 * val result = withRetry(maxAttempts = 3) {
 *     api.fetchData()
 * }
 * 
 * // Custom retry configuration
 * val result = withRetry(
 *     maxAttempts = 5,
 *     initialDelayMs = 1000,
 *     maxDelayMs = 30000,
 *     backoffMultiplier = 2.0,
 *     jitterFactor = 0.1
 * ) {
 *     api.fetchData()
 * }
 * 
 * // Retry only on specific exceptions
 * val result = withRetry(
 *     maxAttempts = 3,
 *     shouldRetry = { it is IOException || it is TimeoutException }
 * ) {
 *     api.fetchData()
 * }
 * ```
 */

private const val TAG = "RetryUtils"

/**
 * Configuration for retry operations.
 */
data class RetryConfig(
    /** Maximum number of retry attempts */
    val maxAttempts: Int = 3,
    /** Initial delay before first retry in milliseconds */
    val initialDelayMs: Long = 1000,
    /** Maximum delay between retries in milliseconds */
    val maxDelayMs: Long = 30000,
    /** Multiplier for exponential backoff */
    val backoffMultiplier: Double = 2.0,
    /** Jitter factor (0.0-1.0) to randomize delays */
    val jitterFactor: Double = 0.1,
    /** Predicate to determine if an exception should trigger a retry */
    val shouldRetry: (Exception) -> Boolean = { true },
    /** Callback for retry attempts */
    val onRetry: (attempt: Int, exception: Exception, delayMs: Long) -> Unit = { _, _, _ -> }
)

/**
 * Execute an operation with retry logic and exponential backoff.
 * 
 * @param config Retry configuration
 * @param operation The operation to execute
 * @return Result of the operation
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(),
    operation: suspend () -> T
): T {
    var attempt = 0
    var lastException: Exception? = null
    var delay = config.initialDelayMs

    while (attempt < config.maxAttempts) {
        try {
            return operation()
        } catch (e: Exception) {
            lastException = e
            attempt++
            
            // Check if we should retry this exception
            if (!config.shouldRetry(e) || attempt >= config.maxAttempts) {
                throw e
            }
            
            // Calculate delay with exponential backoff and jitter
            delay = calculateDelay(delay, config)
            
            // Log retry attempt
            Log.w(TAG, "Attempt $attempt/${config.maxAttempts} failed: ${e.message}, retrying in ${delay}ms")
            
            // Callback
            config.onRetry(attempt, e, delay)
            
            // Wait before retrying
            delay(delay)
        }
    }
    
    // This should never be reached, but just in case
    throw lastException ?: Exception("Unknown retry error")
}

/**
 * Execute an operation with retry logic and return a Result.
 * 
 * @param config Retry configuration
 * @param operation The operation to execute
 * @return Result.Success with value or Result.Failure with last exception
 */
suspend fun <T> withRetryResult(
    config: RetryConfig = RetryConfig(),
    operation: suspend () -> T
): Result<T> {
    return try {
        Result.success(withRetry(config, operation))
    } catch (e: Exception) {
        Log.e(TAG, "All retry attempts failed", e)
        Result.failure(e)
    }
}

/**
 * Execute an operation with retry logic, returning null on failure.
 * 
 * @param config Retry configuration
 * @param operation The operation to execute
 * @return Result of operation or null if all retries fail
 */
suspend fun <T> withRetryOrNull(
    config: RetryConfig = RetryConfig(),
    operation: suspend () -> T
): T? {
    return withRetryResult(config, operation).getOrNull()
}

/**
 * Calculate delay with exponential backoff and jitter.
 */
private fun calculateDelay(currentDelay: Long, config: RetryConfig): Long {
    // Apply exponential backoff
    var delay = (currentDelay * config.backoffMultiplier).toLong()
    
    // Cap at max delay
    delay = min(delay, config.maxDelayMs)
    
    // Add jitter (random variation)
    if (config.jitterFactor > 0) {
        val jitter = (Math.random() * 2 - 1) * config.jitterFactor * delay
        delay = (delay + jitter).toLong()
    }
    
    return delay.coerceAtLeast(0)
}

/**
 * Predefined retry configurations for common use cases.
 */
object RetryPresets {
    
    /**
     * Default retry configuration.
     */
    val DEFAULT = RetryConfig(
        maxAttempts = 3,
        initialDelayMs = 1000,
        maxDelayMs = 30000,
        backoffMultiplier = 2.0,
        jitterFactor = 0.1
    )
    
    /**
     * Aggressive retry for critical operations.
     */
    val AGGRESSIVE = RetryConfig(
        maxAttempts = 5,
        initialDelayMs = 500,
        maxDelayMs = 10000,
        backoffMultiplier = 1.5,
        jitterFactor = 0.2
    )
    
    /**
     * Conservative retry for non-critical operations.
     */
    val CONSERVATIVE = RetryConfig(
        maxAttempts = 2,
        initialDelayMs = 2000,
        maxDelayMs = 15000,
        backoffMultiplier = 2.0,
        jitterFactor = 0.0
    )
    
    /**
     * Network-specific retry with longer timeouts.
     */
    val NETWORK = RetryConfig(
        maxAttempts = 3,
        initialDelayMs = 2000,
        maxDelayMs = 60000,
        backoffMultiplier = 2.0,
        jitterFactor = 0.1,
        shouldRetry = { it is java.io.IOException || it is java.net.SocketTimeoutException }
    )
    
    /**
     * Database-specific retry.
     */
    val DATABASE = RetryConfig(
        maxAttempts = 2,
        initialDelayMs = 1000,
        maxDelayMs = 10000,
        backoffMultiplier = 2.0,
        jitterFactor = 0.0
    )
    
    /**
     * No retry - execute once.
     */
    val NONE = RetryConfig(
        maxAttempts = 1,
        initialDelayMs = 0,
        maxDelayMs = 0,
        backoffMultiplier = 1.0,
        jitterFactor = 0.0
    )
}

/**
 * Extension function for easy retry on suspend functions.
 */
suspend fun <T> (suspend () -> T).withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 30000,
    backoffMultiplier: Double = 2.0,
    jitterFactor: Double = 0.1,
    shouldRetry: (Exception) -> Boolean = { true },
    onRetry: (attempt: Int, exception: Exception, delayMs: Long) -> Unit = { _, _, _ -> }
): T {
    return withRetry(
        RetryConfig(
            maxAttempts = maxAttempts,
            initialDelayMs = initialDelayMs,
            maxDelayMs = maxDelayMs,
            backoffMultiplier = backoffMultiplier,
            jitterFactor = jitterFactor,
            shouldRetry = shouldRetry,
            onRetry = onRetry
        )
    ) { this() }
}
