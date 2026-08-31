package app.readylytics.health.benchmark

import android.content.Context
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
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

    fun heartRateDao(): HeartRateDao

    fun minuteBucketDao(): MinuteBucketDao
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
        seedWarmTierBoundary(entryPoint, zoneId)
    }

    /**
     * R2 Phase-0: seeds one fully warm day (120 days back, outside the 90-day hot tier) so
     * benchmark journeys can exercise warm-tier reads without a real 90-day wait. `heart_rate_records`
     * holds no FK on sessionId, so the buckets are safe even if no matching sleep row exists.
     */
    private suspend fun seedWarmTierBoundary(
        entryPoint: BenchmarkSeedEntryPoint,
        zoneId: ZoneId,
    ) {
        val warmDay = LocalDate.now(zoneId).minusDays(120)
        val warmStartMs = warmDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
        if (entryPoint.minuteBucketDao().count() == 0) {
            entryPoint.minuteBucketDao().upsertBuckets(
                (0 until 1_440).map { i ->
                    HrMinuteBucketEntity(
                        bucketStartMs = warmStartMs + i * 60_000L,
                        bucketEndMs = warmStartMs + (i + 1) * 60_000L,
                        minBpm = 55,
                        maxBpm = 67,
                        avgBpm = 61.0,
                        sampleCount = 60,
                        recordType = "SLEEP",
                        sessionId = "bench-warm-sleep",
                    )
                },
            )
        }
    }
}
