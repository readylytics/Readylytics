package app.readylytics.health.benchmark

import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

internal const val BENCHMARK_SEED_DAYS = 180
private const val SEED_RANDOM_SEED = 20260202L
private const val BASE_HRV_MS = 45
private const val BASE_RHR_BPM = 58
private const val BASE_SPO2_PERCENT = 96.5f
private const val SLEEP_DURATION_MINUTES = 480

internal data class BenchmarkSeedData(
    val summaries: List<DailySummaryEntity>,
    val sleepSessions: List<SleepSessionEntity>,
)

internal fun buildBenchmarkSeedData(
    today: LocalDate,
    zoneId: ZoneId,
): BenchmarkSeedData {
    val random = Random(SEED_RANDOM_SEED)
    val dates = (0 until BENCHMARK_SEED_DAYS).map { today.minusDays(it.toLong()) }

    val summaries =
        dates.mapIndexed { index, date ->
            val trimp = 60f + (index % 14) * 3f
            DailySummaryEntity(
                dateMidnightMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                nocturnalHrv = (BASE_HRV_MS + random.nextInt(-8, 9)).coerceIn(20, 80),
                restingHeartRate = (BASE_RHR_BPM + random.nextInt(-5, 6)).coerceIn(40, 75),
                avgSleepingSpo2 =
                    (BASE_SPO2_PERCENT + random.nextFloat() * 3f - 1.5f)
                        .coerceIn(90f, 99f),
                hrvMuMssd = BASE_HRV_MS.toFloat(),
                rhrBpm = BASE_RHR_BPM.toFloat(),
                trimpWorkoutOnly = trimp,
                trimpEverydayHr = trimp + 12f,
                isCalibrating = false,
            )
        }

    val sleepSessions =
        dates.map { date ->
            val end = date.atTime(7, 0).atZone(zoneId).toInstant()
            val start = end.minus(SLEEP_DURATION_MINUTES.toLong(), ChronoUnit.MINUTES)
            SleepSessionEntity(
                id = "benchmark-sleep-$date",
                startTime = start.toEpochMilli(),
                endTime = end.toEpochMilli(),
                durationMinutes = SLEEP_DURATION_MINUTES,
                efficiency = 95.8f,
                deepSleepMinutes = 100,
                remSleepMinutes = 100,
                lightSleepMinutes = 260,
                awakeMinutes = 20,
                startZoneOffsetSeconds = start.atZone(zoneId).offset.totalSeconds,
                endZoneOffsetSeconds = end.atZone(zoneId).offset.totalSeconds,
                deviceName = "Readylytics benchmark seed",
            )
        }

    return BenchmarkSeedData(summaries = summaries, sleepSessions = sleepSessions)
}
