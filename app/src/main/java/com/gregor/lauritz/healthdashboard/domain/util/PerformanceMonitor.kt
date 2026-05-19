package com.gregor.lauritz.healthdashboard.domain.util

import android.util.Log
import kotlin.system.measureTimeMillis

/**
 * Utility for measuring operation performance and logging metrics.
 * Used for identifying bottlenecks in sync, date operations, and state flows.
 */
object PerformanceMonitor {
    private const val TAG = "PerfMonitor"

    /**
     * Measure and log the execution time of a block.
     *
     * @param operationName Human-readable name for the operation
     * @param block The code block to measure
     * @return The result of the block
     */
    inline fun <T> measure(
        operationName: String,
        block: () -> T,
    ): T {
        var result: T? = null
        val duration =
            measureTimeMillis {
                result = block()
            }
        logDuration(operationName, duration)
        return result!!
    }

    /**
     * Measure and log the execution time of a suspend block.
     */
    suspend inline fun <T> measureAsync(
        operationName: String,
        crossinline block: suspend () -> T,
    ): T {
        var result: T? = null
        val duration =
            measureTimeMillis {
                result = block()
            }
        logDuration(operationName, duration)
        return result!!
    }

    private fun logDuration(
        operationName: String,
        durationMs: Long,
    ) {
        val level =
            when {
                durationMs > 1000 -> "SLOW"
                durationMs > 100 -> "MEDIUM"
                else -> "FAST"
            }
        Log.d(TAG, "[$level] $operationName took ${durationMs}ms")
    }

    /**
     * Flag specific operations for production telemetry collection.
     */
    data class PerformanceEvent(
        val operationName: String,
        val durationMs: Long,
        val isSlowOperation: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )
}
