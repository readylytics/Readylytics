package app.readylytics.health.domain.scoring.golden

import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.SleepStageEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.domain.model.RecordType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Synthetic large-volume dataset generator (WP-02a of the architecture/HC/scoring remediation
 * plan): produces a stress fixture other phases' benchmarks (WP-02b, and later PERF-001/PERF-002/
 * HC-001 fixes) measure against. Defaults to ~1M heart-rate samples concentrated in the most
 * recent 30-day "dense window" (matching the plan's ~0.4 Hz real-world estimate), plus a sparse
 * `historyDays`-long date-range skeleton (~1,800 sleep sessions, ~1,000 workouts, nightly HRV,
 * ~20% alternate-device rows) so range queries have realistic historical depth to scan without
 * every one of those 3,650 days being sample-dense.
 *
 * Writes through a **file-backed** [HealthDatabase] (not in-memory) -- at 1M+ rows, in-memory
 * SQLite risks pressuring the JVM test heap, which has no explicit `-Xmx` configured today.
 */
class SyntheticDatasetGenerator(
    private val zoneId: ZoneId,
    seed: Long = 20260202L,
) {
    private val random = Random(seed)

    data class Config(
        val historyDays: Int = 3650,
        val denseWindowDays: Int = 30,
        val heartRateSamplesInDenseWindow: Int = 1_000_000,
        val sleepSessionCount: Int = 1_800,
        val workoutCount: Int = 1_000,
        val alternateDeviceFraction: Double = 0.2,
        val flushEveryRows: Int = 50_000,
    )

    class GenerationSummary(
        val heartRateRowCount: Long,
        val hrvRowCount: Long,
        val sleepSessionCount: Int,
        val workoutCount: Int,
        val historyStartDate: LocalDate,
        val denseWindowStartDate: LocalDate,
    )

    suspend fun generate(
        db: HealthDatabase,
        endDate: LocalDate,
        config: Config = Config(),
    ): GenerationSummary {
        val historyStartDate = endDate.minusDays((config.historyDays - 1).toLong())
        val denseWindowStartDate = endDate.minusDays((config.denseWindowDays - 1).toLong())

        val sleepSessionDao = db.sleepSessionDao()
        val sleepStageDao = db.sleepStageDao()
        val heartRateDao = db.heartRateDao()
        val hrvDao = db.hrvDao()
        val workoutDao = db.workoutDao()

        var heartRateRowCount = 0L
        var hrvRowCount = 0L

        val hrBuffer = mutableListOf<HeartRateRecordEntity>()
        suspend fun flushHr(force: Boolean = false) {
            if (hrBuffer.isEmpty()) return
            if (force || hrBuffer.size >= config.flushEveryRows) {
                heartRateDao.upsertAll(hrBuffer)
                heartRateRowCount += hrBuffer.size
                hrBuffer.clear()
            }
        }

        fun deviceName(): String? =
            if (random.nextDouble() < config.alternateDeviceFraction) "AlternateWatch" else "PrimaryWatch"

        // --- Sparse skeleton: sleep sessions + HRV + workouts spread across the full history. ---
        val sleepStepDays = (config.historyDays.toDouble() / config.sleepSessionCount).coerceAtLeast(1.0)
        val sleepSessions = mutableListOf<SleepSessionEntity>()
        val sleepStages = mutableListOf<SleepStageEntity>()
        val hrvRows = mutableListOf<HrvRecordEntity>()
        var accumulatedSleepDays = 0.0
        var sleepIndex = 0
        while (sleepIndex < config.sleepSessionCount) {
            val wakeDay = historyStartDate.plusDays(accumulatedSleepDays.toLong())
            if (wakeDay.isAfter(endDate)) break
            val id = "synthetic_sleep_$sleepIndex"
            val bedTime = wakeDay.minusDays(1).atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
            val wakeTime = wakeDay.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
            val device = deviceName()

            sleepSessions +=
                SleepSessionEntity(
                    id = id,
                    startTime = bedTime,
                    endTime = wakeTime,
                    durationMinutes = 420,
                    efficiency = 90f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 210,
                    awakeMinutes = 30,
                    deviceName = device,
                )
            sleepStages +=
                SleepStageEntity(
                    sessionId = id,
                    stageType = "LIGHT",
                    startTime = bedTime,
                    endTime = wakeTime,
                    durationMinutes = 420,
                )
            hrvRows +=
                HrvRecordEntity(
                    id = "synthetic_hrv_$sleepIndex",
                    timestampMs = bedTime + 3 * 60 * 60_000L,
                    rmssdMs = 35f + random.nextInt(15),
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = device,
                )

            sleepIndex++
            accumulatedSleepDays += sleepStepDays
        }
        sleepSessionDao.upsertAll(sleepSessions)
        sleepStageDao.upsertAll(sleepStages)
        hrvDao.upsertAll(hrvRows)
        hrvRowCount += hrvRows.size

        val workoutStepDays = (config.historyDays.toDouble() / config.workoutCount).coerceAtLeast(1.0)
        val workoutRows = mutableListOf<WorkoutRecordEntity>()
        var accumulatedWorkoutDays = 0.0
        var workoutIndex = 0
        while (workoutIndex < config.workoutCount) {
            val day = historyStartDate.plusDays(accumulatedWorkoutDays.toLong())
            if (day.isAfter(endDate)) break
            val start = day.atTime(18, 0).atZone(zoneId).toInstant().toEpochMilli()
            val durationMinutes = 30 + random.nextInt(30)
            workoutRows +=
                WorkoutRecordEntity(
                    id = "synthetic_workout_$workoutIndex",
                    startTime = start,
                    endTime = start + durationMinutes * 60_000L,
                    exerciseType = "RUNNING",
                    durationMinutes = durationMinutes,
                    zone1Minutes = durationMinutes * 0.1f,
                    zone2Minutes = durationMinutes * 0.3f,
                    zone3Minutes = durationMinutes * 0.4f,
                    zone4Minutes = durationMinutes * 0.15f,
                    zone5Minutes = durationMinutes * 0.05f,
                    trimp = durationMinutes * 1.5f,
                    avgHr = 140f,
                    deviceName = deviceName(),
                )
            workoutIndex++
            accumulatedWorkoutDays += workoutStepDays
        }
        workoutDao.upsertAll(workoutRows)

        // --- Dense window: ~1M HR samples across the most recent `denseWindowDays` days. ---
        val denseWindowStartMs = denseWindowStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val denseWindowEndMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val denseWindowMs = denseWindowEndMs - denseWindowStartMs
        val sampleIntervalMs = (denseWindowMs / config.heartRateSamplesInDenseWindow).coerceAtLeast(1L)

        var sampleTime = denseWindowStartMs
        var sampleSeq = 0L
        while (sampleTime < denseWindowEndMs) {
            hrBuffer +=
                HeartRateRecordEntity(
                    id = "synthetic_hr_$sampleSeq",
                    timestampMs = sampleTime,
                    beatsPerMinute = 55 + random.nextInt(60),
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = deviceName(),
                )
            sampleSeq++
            sampleTime += sampleIntervalMs
            flushHr()
        }
        flushHr(force = true)

        return GenerationSummary(
            heartRateRowCount = heartRateRowCount,
            hrvRowCount = hrvRowCount,
            sleepSessionCount = sleepSessions.size,
            workoutCount = workoutRows.size,
            historyStartDate = historyStartDate,
            denseWindowStartDate = denseWindowStartDate,
        )
    }
}
