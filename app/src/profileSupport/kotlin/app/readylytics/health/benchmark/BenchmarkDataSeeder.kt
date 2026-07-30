package app.readylytics.health.benchmark

import android.content.Context
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.ZoneId

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BenchmarkSeedEntryPoint {
    fun dailySummaryDao(): DailySummaryDao

    fun sleepSessionDao(): SleepSessionDao
}

/**
 * Only compiled into performance build types. Seeds deterministic benchmark
 * rows once so benchmark journeys have real chart and summary content.
 */
internal object BenchmarkDataSeeder {
    suspend fun seedIfNeeded(context: Context) {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context,
                BenchmarkSeedEntryPoint::class.java,
            )
        val zoneId = ZoneId.systemDefault()
        val data = buildBenchmarkSeedData(LocalDate.now(zoneId), zoneId)

        if (entryPoint.dailySummaryDao().count() < BENCHMARK_SEED_DAYS) {
            entryPoint.dailySummaryDao().upsertAll(data.summaries)
        }
        if (entryPoint.sleepSessionDao().count() < BENCHMARK_SEED_DAYS) {
            entryPoint.sleepSessionDao().upsertAll(data.sleepSessions)
        }
    }
}
