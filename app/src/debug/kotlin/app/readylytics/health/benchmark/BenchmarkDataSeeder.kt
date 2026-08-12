package app.readylytics.health.benchmark

import android.content.Context

/**
 * No-op for this build type. app/src/benchmark provides the real
 * implementation (same package + object name) that actually seeds
 * deterministic daily_summaries rows for Macrobenchmark UI journeys — see
 * ScrollBenchmark in the :benchmark module. This exact file also exists in
 * the sibling debug/release build type — main has no copy at all, so
 * exactly one of {debug, release, benchmark} is on the compile path per
 * variant and there is no redeclaration conflict.
 */
internal object BenchmarkDataSeeder {
    suspend fun seedIfNeeded(context: Context) = Unit
}
