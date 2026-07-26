package app.readylytics.health.benchmark

import android.content.Context
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

private const val SEED_DAYS = 180
private const val SEED_RANDOM_SEED = 20260202L
private const val BASE_HRV_MS = 45
private const val BASE_RHR_BPM = 58
private const val BASE_SPO2_PERCENT = 96.5f

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BenchmarkSeedEntryPoint {
    fun dailySummaryDao(): DailySummaryDao
}

/**
 * Only compiled into the "benchmark" build type. Seeds 180 days of
 * deterministic daily_summaries rows once (idempotent via a row-count check)
 * so Vitals' three trend charts, both gauges, and the Dashboard summary card
 * have real content instead of empty-state skeletons during ScrollBenchmark
 * journeys. 180 days covers all three TimeRange selections (7D/30D/180D).
 */
internal object BenchmarkDataSeeder {
    suspend fun seedIfNeeded(context: Context) {
        val dao =
            EntryPointAccessors
                .fromApplication(context, BenchmarkSeedEntryPoint::class.java)
                .dailySummaryDao()
        if (dao.count() == 0) {
            dao.upsertAll(buildSeedRows())
        }
    }

    private fun buildSeedRows(): List<DailySummaryEntity> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val random = Random(SEED_RANDOM_SEED)
        return (0 until SEED_DAYS).map { dayOffset ->
            val date = today.minusDays(dayOffset.toLong())
            DailySummaryEntity(
                dateMidnightMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                nocturnalHrv = (BASE_HRV_MS + random.nextInt(-8, 9)).coerceIn(20, 80),
                restingHeartRate = (BASE_RHR_BPM + random.nextInt(-5, 6)).coerceIn(40, 75),
                avgSleepingSpo2 = (BASE_SPO2_PERCENT + random.nextFloat() * 3f - 1.5f).coerceIn(90f, 99f),
                hrvMuMssd = BASE_HRV_MS.toFloat(),
                rhrBpm = BASE_RHR_BPM.toFloat(),
                isCalibrating = false,
            )
        }
    }
}
