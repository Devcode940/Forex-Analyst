package com.devcode940.ForexAnalyst.data.utils

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Production-ready Circuit Breaker pattern implementation.
 * 
 * Prevents cascading failures by temporarily blocking requests after a threshold
 * of failures is reached. Automatically resets after a timeout period.
 * 
 * Features:
 * - Thread-safe with Atomic variables
 * - Configurable failure threshold and reset timeout
 * - Three states: CLOSED, OPEN, HALF_OPEN
 * - Automatic state transitions
 * - Comprehensive logging
 * 
 * Usage:
 * ```kotlin
 * val circuitBreaker = CircuitBreaker(failureThreshold = 5, resetTimeoutMs = 30000)
 * 
 * try {
 *     if (circuitBreaker.canExecute()) {
 *         val result = riskyOperation()
 *         circuitBreaker.recordSuccess()
 *         return result
 *     } else {
 *         throw CircuitBreakerOpenException("Circuit breaker is open")
 *     }
 * } catch (e: Exception) {
 *     circuitBreaker.recordFailure()
 *     throw e
 * }
 * ```
 */
class CircuitBreaker(
    private val name: String = "default",
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30000,
    private val halfOpenMaxAttempts: Int = 3
) {
    
    companion object {
        private const val TAG = "CircuitBreaker"
    }
    
    /**
     * States of the circuit breaker.
     */
    enum class State {
        /** Normal operation - requests allowed */
        CLOSED,
        /** Failed state - requests blocked */
        OPEN,
        /** Testing state - limited requests allowed */
        HALF_OPEN
    }
    
    private val state = AtomicReference(State.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0L)
    private val halfOpenAttempts = AtomicInteger(0)
    
    /**
     * Check if a request can be executed.
     * 
     * @return true if request can proceed, false if circuit is open
     */
    fun canExecute(): Boolean {
        val currentState = state.get()
        
        return when (currentState) {
            State.CLOSED -> true
            State.OPEN -> {
                // Check if reset timeout has passed
                if (System.currentTimeMillis() - lastFailureTime.get() > resetTimeoutMs) {
                    transitionToHalfOpen()
                    true
                } else {
                    Log.w(TAG, "[$name] Circuit breaker is OPEN - rejecting request")
                    false
                }
            }
            State.HALF_OPEN -> {
                // Allow limited requests in half-open state
                val attempts = halfOpenAttempts.incrementAndGet()
                if (attempts <= halfOpenMaxAttempts) {
                    Log.i(TAG, "[$name] Circuit breaker is HALF_OPEN - allowing attempt $attempts/$halfOpenMaxAttempts")
                    true
                } else {
                    Log.w(TAG, "[$name] Circuit breaker HALF_OPEN - max attempts reached")
                    false
                }
            }
        }
    }
    
    /**
     * Record a successful operation.
     * Resets failure count and transitions to CLOSED state if in HALF_OPEN.
     */
    fun recordSuccess() {
        failureCount.set(0)
        
        // If we were in half-open state, transition back to closed
        if (state.get() == State.HALF_OPEN) {
            transitionToClosed()
        }
        
        Log.d(TAG, "[$name] Success recorded - circuit breaker healthy")
    }
    
    /**
     * Record a failed operation.
     * Increments failure count and may transition to OPEN state.
     */
    fun recordFailure() {
        val count = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        
        Log.w(TAG, "[$name] Failure recorded ($count/$failureThreshold)")
        
        if (count >= failureThreshold) {
            transitionToOpen()
        }
    }
    
    /**
     * Record a failed operation with exception.
     */
    fun recordFailure(exception: Exception) {
        recordFailure()
        Log.e(TAG, "[$name] Failure with exception: ${exception.message}", exception)
    }
    
    /**
     * Manually reset the circuit breaker to CLOSED state.
     */
    fun reset() {
        transitionToClosed()
        Log.i(TAG, "[$name] Circuit breaker manually reset")
    }
    
    /**
     * Get the current state of the circuit breaker.
     */
    fun getState(): State = state.get()
    
    /**
     * Get current failure count.
     */
    fun getFailureCount(): Int = failureCount.get()
    
    /**
     * Get time since last failure in milliseconds.
     */
    fun getTimeSinceLastFailure(): Long {
        return System.currentTimeMillis() - lastFailureTime.get()
    }
    
    /**
     * Get circuit breaker statistics.
     */
    fun getStats(): Stats {
        return Stats(
            name = name,
            state = state.get(),
            failureCount = failureCount.get(),
            failureThreshold = failureThreshold,
            timeSinceLastFailure = getTimeSinceLastFailure(),
            resetTimeout = resetTimeoutMs,
            halfOpenAttempts = halfOpenAttempts.get(),
            halfOpenMaxAttempts = halfOpenMaxAttempts
        )
    }
    
    /**
     * Transition to OPEN state.
     */
    private fun transitionToOpen() {
        if (state.compareAndSet(State.CLOSED, State.OPEN) || 
            state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            halfOpenAttempts.set(0)
            Log.w(TAG, "[$name] Circuit breaker transitioned to OPEN state")
        }
    }
    
    /**
     * Transition to CLOSED state.
     */
    private fun transitionToClosed() {
        if (state.compareAndSet(State.OPEN, State.CLOSED) || 
            state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            failureCount.set(0)
            halfOpenAttempts.set(0)
            Log.i(TAG, "[$name] Circuit breaker transitioned to CLOSED state")
        }
    }
    
    /**
     * Transition to HALF_OPEN state.
     */
    private fun transitionToHalfOpen() {
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            halfOpenAttempts.set(0)
            Log.i(TAG, "[$name] Circuit breaker transitioned to HALF_OPEN state")
        }
    }
    
    /**
     * Statistics for monitoring circuit breaker state.
     */
    data class Stats(
        val name: String,
        val state: State,
        val failureCount: Int,
        val failureThreshold: Int,
        val timeSinceLastFailure: Long,
        val resetTimeout: Long,
        val halfOpenAttempts: Int,
        val halfOpenMaxAttempts: Int
    ) {
        fun isHealthy(): Boolean = state == State.CLOSED
        fun isOpen(): Boolean = state == State.OPEN
        fun isHalfOpen(): Boolean = state == State.HALF_OPEN
    }
}

/**
 * Exception thrown when circuit breaker is open and requests are rejected.
 */
class CircuitBreakerOpenException(message: String) : Exception(message)

/**
 * Factory for creating circuit breakers with common configurations.
 */
object CircuitBreakerFactory {
    
    /**
     * Create a circuit breaker for network operations.
     */
    fun createNetworkCircuitBreaker(name: String = "network"): CircuitBreaker {
        return CircuitBreaker(
            name = name,
            failureThreshold = 5,
            resetTimeoutMs = 30000, // 30 seconds
            halfOpenMaxAttempts = 3
        )
    }
    
    /**
     * Create a circuit breaker for database operations.
     */
    fun createDatabaseCircuitBreaker(name: String = "database"): CircuitBreaker {
        return CircuitBreaker(
            name = name,
            failureThreshold = 10,
            resetTimeoutMs = 60000, // 60 seconds
            halfOpenMaxAttempts = 5
        )
    }
    
    /**
     * Create a circuit breaker for external API calls.
     */
    fun createApiCircuitBreaker(name: String = "api"): CircuitBreaker {
        return CircuitBreaker(
            name = name,
            failureThreshold = 3,
            resetTimeoutMs = 15000, // 15 seconds
            halfOpenMaxAttempts = 2
        )
    }
}
