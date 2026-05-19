package com.gregor.lauritz.healthdashboard.util

import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Utilities for load testing date operations and concurrent sync scenarios.
 * Used during development and pre-release testing to identify race conditions.
 */

/**
 * Simulate rapid foreground/background cycles with concurrent date operations.
 * Useful for stress-testing date state consistency.
 *
 * @param iterations Number of cycles to perform
 * @param concurrency Number of concurrent operations
 * @param operation The operation to repeatedly execute (e.g., onAppForeground)
 * @return Duration in milliseconds and any exceptions encountered
 */
suspend fun stressTestForegroundCycles(
    iterations: Int = 100,
    concurrency: Int = 10,
    operation: suspend () -> Unit,
): LoadTestResult {
    val exceptions = mutableListOf<Throwable>()
    val duration = measureTimeMillis {
        repeat(iterations / concurrency) {
            val jobs = (1..concurrency).map {
                kotlinx.coroutines.launch {
                    try {
                        operation()
                    } catch (e: Throwable) {
                        exceptions.add(e)
                    }
                }
            }
            // Synthetic delay to vary timing
            kotlinx.coroutines.delay(Random.nextLong(1, 50))
            jobs.forEach { it.join() }
        }
    }
    return LoadTestResult(
        durationMs = duration,
        iterationsCompleted = iterations,
        exceptionCount = exceptions.size,
        exceptions = exceptions,
    )
}

/**
 * Simulate rapid date navigation (previous/next day) under load.
 *
 * @param iterations Number of navigation operations
 * @param operation The operation to execute (e.g., onNextDay, onPreviousDay)
 * @return Result with timing and consistency check
 */
suspend fun stressTestDateNavigation(
    iterations: Int = 1000,
    operation: suspend () -> Unit,
): LoadTestResult {
    val exceptions = mutableListOf<Throwable>()
    val duration = measureTimeMillis {
        repeat(iterations) {
            try {
                operation()
            } catch (e: Throwable) {
                exceptions.add(e)
            }
        }
    }
    return LoadTestResult(
        durationMs = duration,
        iterationsCompleted = iterations,
        exceptionCount = exceptions.size,
        exceptions = exceptions,
    )
}

/**
 * Result of a load test execution.
 */
data class LoadTestResult(
    val durationMs: Long,
    val iterationsCompleted: Int,
    val exceptionCount: Int,
    val exceptions: List<Throwable> = emptyList(),
) {
    val operationsPerSecond: Double =
        (iterationsCompleted * 1000.0) / durationMs

    val successRate: Double =
        ((iterationsCompleted - exceptionCount) * 100.0) / iterationsCompleted

    fun printSummary() {
        println(
            """
            Load Test Summary
            =================
            Duration: ${durationMs}ms
            Operations: $iterationsCompleted
            Success Rate: ${String.format("%.2f", successRate)}%
            Ops/sec: ${String.format("%.0f", operationsPerSecond)}
            Exceptions: $exceptionCount
            """.trimIndent(),
        )
        if (exceptions.isNotEmpty()) {
            println("\nExceptions encountered:")
            exceptions.take(5).forEach {
                println("  - ${it::class.simpleName}: ${it.message}")
            }
            if (exceptions.size > 5) {
                println("  ... and ${exceptions.size - 5} more")
            }
        }
    }
}
