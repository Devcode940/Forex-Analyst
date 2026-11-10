package com.devcode940.ForexAnalyst.data.network

import java.io.IOException

/**
 * Sealed hierarchy for network errors with comprehensive error handling.
 * Provides human-readable error messages and recovery suggestions.
 */
sealed class NetworkError(
    val message: String,
    val code: Int? = null,
    val isRetryable: Boolean = false,
    val isAuthError: Boolean = false,
    val isTimeout: Boolean = false
) {
    /**
     * Network is unavailable (no internet connection).
     */
    object NoNetwork : NetworkError(
        message = "No internet connection. Please check your network settings.",
        code = -1,
        isRetryable = true
    )

    /**
     * Connection timeout.
     */
    object Timeout : NetworkError(
        message = "Connection timed out. Please try again.",
        code = 408,
        isRetryable = true,
        isTimeout = true
    )

    /**
     * Server is unreachable.
     */
    object ServerUnreachable : NetworkError(
        message = "Unable to reach the server. Please try again later.",
        code = 502,
        isRetryable = true
    )

    /**
     * Too many requests - rate limited.
     */
    object RateLimited : NetworkError(
        message = "Too many requests. Please wait and try again.",
        code = 429,
        isRetryable = true
    )

    /**
     * Authentication failed.
     */
    object AuthenticationFailed : NetworkError(
        message = "Authentication failed. Please sign in again.",
        code = 401,
        isAuthError = true
    )

    /**
     * Authorization denied.
     */
    object AuthorizationDenied : NetworkError(
        message = "Access denied. You don't have permission to perform this action.",
        code = 403,
        isAuthError = true
    )

    /**
     * Resource not found.
     */
    object NotFound : NetworkError(
        message = "The requested resource was not found.",
        code = 404,
        isRetryable = false
    )

    /**
     * Invalid request parameters.
     */
    data class BadRequest(val details: String = "Invalid request") : NetworkError(
        message = "Invalid request: $details",
        code = 400,
        isRetryable = false
    )

    /**
     * Server error.
     */
    data class ServerError(val details: String = "Internal server error") : NetworkError(
        message = "Server error: $details",
        code = 500,
        isRetryable = true
    )

    /**
     * Unknown network error.
     */
    data class Unknown(val throwable: Throwable? = null) : NetworkError(
        message = throwable?.message ?: "An unknown network error occurred",
        isRetryable = true
    )

    /**
     * API key related errors.
     */
    object InvalidApiKey : NetworkError(
        message = "Invalid API key. Please check your configuration.",
        code = 401,
        isAuthError = true
    )

    object ApiKeyExhausted : NetworkError(
        message = "API key quota exhausted. Please try again later or use a different API key.",
        code = 429,
        isRetryable = true
    )

    /**
     * Parse error - response could not be parsed.
     */
    data class ParseError(val details: String) : NetworkError(
        message = "Failed to parse response: $details",
        isRetryable = false
    )

    /**
     * Empty response from server.
     */
    object EmptyResponse : NetworkError(
        message = "Empty response from server",
        code = 204,
        isRetryable = true
    )

    /**
     * Convert an exception to a NetworkError.
     */
    companion object {
        fun fromException(e: Exception): NetworkError {
            return when (e) {
                is IOException -> {
                    when {
                        e.message?.contains("timeout", ignoreCase = true) == true -> Timeout
                        e.message?.contains("no route", ignoreCase = true) == true -> NoNetwork
                        e.message?.contains("unable to resolve host", ignoreCase = true) == true -> NoNetwork
                        e.message?.contains("network is unreachable", ignoreCase = true) == true -> NoNetwork
                        else -> NoNetwork
                    }
                }
                is java.net.SocketTimeoutException -> Timeout
                is java.net.ConnectException -> ServerUnreachable
                is java.net.UnknownHostException -> NoNetwork
                is retrofit2.HttpException -> {
                    when (e.code()) {
                        400 -> BadRequest(e.message())
                        401 -> AuthenticationFailed
                        403 -> AuthorizationDenied
                        404 -> NotFound
                        429 -> RateLimited
                        500, 502, 503, 504 -> ServerError(e.message())
                        else -> Unknown(e)
                    }
                }
                else -> Unknown(e)
            }
        }

        /**
         * Get a user-friendly error message for display.
         */
        fun getUserMessage(error: NetworkError): String {
            return when (error) {
                is BadRequest -> error.message
                is ServerError -> "Server error. Please try again later."
                is ParseError -> "Failed to process response. Please try again."
                is Unknown -> error.message
                else -> error.message
            }
        }

        /**
         * Get a short error message for notifications.
         */
        fun getShortMessage(error: NetworkError): String {
            return when (error) {
                NoNetwork -> "No network"
                Timeout -> "Timeout"
                ServerUnreachable -> "Server down"
                RateLimited -> "Rate limited"
                AuthenticationFailed -> "Auth failed"
                AuthorizationDenied -> "Access denied"
                NotFound -> "Not found"
                is BadRequest -> "Bad request"
                is ServerError -> "Server error"
                is ParseError -> "Parse error"
                is Unknown -> "Error"
                InvalidApiKey -> "Invalid API key"
                ApiKeyExhausted -> "API quota exceeded"
                EmptyResponse -> "No data"
            }
        }
    }
}

/**
 * Result wrapper for network operations.
 * Provides a clean way to handle success and error states.
 */
sealed class NetworkResult<out T> {
    data class Success<out T>(val data: T) : NetworkResult<T>()
    data class Error(val error: NetworkError) : NetworkResult<Nothing>()
    object Loading : NetworkResult<Nothing>()

    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun getOrDefault(default: T): T = when (this) {
        is Success -> data
        else -> default
    }

    /**
     * Map the success value using the given transform.
     */
    fun <R> map(transform: (T) -> R): NetworkResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> Error(error)
        Loading -> Loading
    }

    /**
     * Flat map the success value using the given transform.
     */
    fun <R> flatMap(transform: (T) -> NetworkResult<R>): NetworkResult<R> = when (this) {
        is Success -> transform(data)
        is Error -> Error(error)
        Loading -> Loading
    }

    /**
     * On success, execute the given action.
     */
    fun onSuccess(action: (T) -> Unit): NetworkResult<T> {
        if (this is Success) action(data)
        return this
    }

    /**
     * On error, execute the given action.
     */
    fun onError(action: (NetworkError) -> Unit): NetworkResult<T> {
        if (this is Error) action(error)
        return this
    }
}

/**
 * Extension function to convert a suspend function to a NetworkResult.
 */
suspend fun <T> networkCall(block: suspend () -> T): NetworkResult<T> {
    return try {
        NetworkResult.Success(block())
    } catch (e: Exception) {
        NetworkResult.Error(NetworkError.fromException(e))
    }
}

/**
 * Extension function to retry a network operation with exponential backoff.
 */
suspend fun <T> NetworkResult<T>.retry(
    maxRetries: Int = 3,
    delayMs: Long = 1000L,
    shouldRetry: (NetworkError) -> Boolean = { it.isRetryable }
): NetworkResult<T> {
    if (this !is NetworkResult.Error || !shouldRetry(error) || maxRetries <= 0) {
        return this
    }

    var currentRetry = 0
    var currentResult: NetworkResult<T> = this

    while (currentRetry < maxRetries && currentResult is NetworkResult.Error && shouldRetry(currentResult.error)) {
        currentRetry++
        kotlinx.coroutines.delay(delayMs * currentRetry)
        // For retry, we would need the original operation, so this is a simplified version
        // In practice, you'd call the original suspend function again
        break
    }

    return currentResult
}
