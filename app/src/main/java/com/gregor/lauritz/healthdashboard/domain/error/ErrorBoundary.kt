package com.gregor.lauritz.healthdashboard.domain.error

import android.util.Log

/**
 * Result wrapper for operations that may fail.
 * Provides safe fallback patterns without throwing.
 */
sealed interface SafeResult<T> {
    data class Success<T>(val value: T) : SafeResult<T>

    data class Failure<T>(
        val error: Throwable,
        val context: String = "",
    ) : SafeResult<T>

    data class PartialSuccess<T>(
        val value: T,
        val warning: String,
    ) : SafeResult<T>
}

/**
 * Wraps an operation in error handling with recovery strategies.
 *
 * @param operationName Human-readable name for logging context
 * @param onError Optional error handler for side effects (logging, metrics)
 * @param operation The code to execute
 * @return Success with operation result, or Failure with exception
 *
 * For fallback values, use: safeOperation(...).getOrElse(fallbackValue)
 */
inline fun <T> safeOperation(
    operationName: String,
    onError: (Throwable) -> Unit = { Log.e("ErrorBoundary", "Error in $operationName", it) },
    operation: () -> T,
): SafeResult<T> =
    try {
        SafeResult.Success(operation())
    } catch (e: Throwable) {
        onError(e)
        SafeResult.Failure(error = e, context = operationName)
    }

/**
 * Extract value from result or return fallback.
 */
fun <T> SafeResult<T>.getOrNull(): T? =
    when (this) {
        is SafeResult.Success -> value
        is SafeResult.PartialSuccess -> value
        is SafeResult.Failure -> null
    }

fun <T> SafeResult<T>.getOrElse(fallback: T): T =
    when (this) {
        is SafeResult.Success -> value
        is SafeResult.PartialSuccess -> value
        is SafeResult.Failure -> fallback
    }

/**
 * Error recovery strategies for common failure patterns.
 */
sealed interface RecoveryStrategy<T> {
    data class ReturnValue<T>(val value: T) : RecoveryStrategy<T>

    data class Retry<T>(
        val maxAttempts: Int = 3,
        val delayMs: Long = 100,
    ) : RecoveryStrategy<T>

    data class ReturnNull<T> : RecoveryStrategy<T>
}

/**
 * Apply a recovery strategy to a failed operation.
 */
suspend inline fun <T> SafeResult.Failure<T>.recover(
    strategy: RecoveryStrategy<T>,
    operation: suspend () -> T,
): SafeResult<T> =
    when (strategy) {
        is RecoveryStrategy.ReturnValue -> SafeResult.Success(strategy.value)
        is RecoveryStrategy.Retry -> {
            repeat(strategy.maxAttempts - 1) {
                kotlinx.coroutines.delay(strategy.delayMs)
                try {
                    return SafeResult.Success(operation())
                } catch (e: Throwable) {
                    // Continue to next attempt
                }
            }
            this
        }
        is RecoveryStrategy.ReturnNull<T> ->
            SafeResult.Failure(error = error, context = context)
    }
