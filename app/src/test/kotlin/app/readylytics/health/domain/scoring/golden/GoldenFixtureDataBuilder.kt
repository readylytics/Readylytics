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
 * Deterministic seeded-data builder for the golden walk-forward regression fixture (WP-01 of
 * internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md).
 *
 * Writes raw rows through the real DAOs the way a chunked Health Connect ingest would: HR/HRV rows
 * are tagged [RecordType.RESTING] with no `sessionId`, mirroring pre-reconcile data, so the
 * walk-forward test's `SessionLinkReconciler.reconcile(...)` pass has real linking work to do,
 * matching production (`ResyncRangeUseCase`'s RECONCILE phase runs before RECOMPUTE).
 *
 * Scenario coverage baked into the generated range: normal nights, one stage-less night (mirrors
 * today's HC-006 behavior: durationMinutes/efficiency = 0, no [SleepStageEntity] rows -- this is
 * intentional so the fixture locks in *current* behavior; a future HC-006 fix should show up as a
 * quantified score delta, not a silent golden-file change), the two DST dates already used by
 * `ScoringPointInTimeRegressionTest` (2025-03-31 / 2025-10-27, `Europe/Berlin`), multi-device
 * provenance (~20% of rows carry an alternate `deviceName`), one multi-day data gap, and one
 * biphasic sleep day (an afternoon nap plus the main night, both landing in the same scoring day).
 */
class GoldenFixtureDataBuilder(
    private val zoneId: ZoneId,
    seed: Long = 20260101L,
) {
    private val random = Random(seed)

    data class ScenarioDates(
        val stageLessNightDate: LocalDate,
        val biphasicDate: LocalDate,
        val gapStart: LocalDate,
        val gapEnd: LocalDate,
        val dstDates: List<LocalDate>,
    )

    class BuildResult(
        val stepsByDate: Map<LocalDate, Long>,
        val scenarioDates: ScenarioDates,
    )

    /**
     * Seeds [db] with data across `[startDate, endDate]` inclusive. [startDate] must be at least
     * 95 days before [endDate] to fit the fixed-offset scenario days below, and the range should
     * comfortably contain 2025-03-31 and 2025-10-27 for the DST scenario to apply.
     */
    suspend fun build(
        db: HealthDatabase,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BuildResult {
        val heartRateDao = db.heartRateDao()
        val hrvDao = db.hrvDao()
        val sleepSessionDao = db.sleepSessionDao()
        val sleepStageDao = db.sleepStageDao()
        val workoutDao = db.workoutDao()

        val stageLessNightDate = startDate.plusDays(30)
        val biphasicDate = startDate.plusDays(60)
        val gapStart = startDate.plusDays(90)
        val gapEnd = gapStart.plusDays(4)
        val dstDates = listOf(LocalDate.of(2025, 3, 31), LocalDate.of(2025, 10, 27))
        val scenarioDates = ScenarioDates(stageLessNightDate, biphasicDate, gapStart, gapEnd, dstDates)

        val sleepSessions = mutableListOf<SleepSessionEntity>()
        val sleepStages = mutableListOf<SleepStageEntity>()
        val heartRateRows = mutableListOf<HeartRateRecordEntity>()
        val hrvRows = mutableListOf<HrvRecordEntity>()
        val workoutRows = mutableListOf<WorkoutRecordEntity>()
        val stepsByDate = mutableMapOf<LocalDate, Long>()

        var day = startDate
        var workoutSeq = 0
        while (!day.isAfter(endDate)) {
            val inGap = !day.isBefore(gapStart) && !day.isAfter(gapEnd)
            stepsByDate[day] = if (inGap) 0L else (3_000L + random.nextInt(9_000))

            if (!inGap) {
                val deviceName = if (random.nextInt(5) == 0) "AlternateWatch" else "PrimaryWatch"
                val isStageLess = day == stageLessNightDate
                val isBiphasic = day == biphasicDate

                addNight(
                    sleepSessions,
                    sleepStages,
                    heartRateRows,
                    day,
                    deviceName,
                    stageLess = isStageLess,
                )
                if (isBiphasic) {
                    addNap(sleepSessions, sleepStages, heartRateRows, day, deviceName)
                }
                if (!isStageLess) {
                    addNightlyHrv(hrvRows, day, deviceName)
                }
                addDaytimeRestingHr(heartRateRows, day, deviceName)

                // A workout roughly every third day, alternating device provenance.
                if (day.toEpochDay() % 3L == 0L) {
                    workoutSeq++
                    addWorkout(workoutRows, heartRateRows, day, deviceName, workoutSeq)
                }
            }

            day = day.plusDays(1)
        }

        sleepSessionDao.upsertAll(sleepSessions)
        sleepStageDao.upsertAll(sleepStages)
        heartRateDao.upsertAll(heartRateRows)
        hrvDao.upsertAll(hrvRows)
        workoutDao.upsertAll(workoutRows)

        return BuildResult(stepsByDate, scenarioDates)
    }

    private fun addNight(
        sleepSessions: MutableList<SleepSessionEntity>,
        sleepStages: MutableList<SleepStageEntity>,
        heartRateRows: MutableList<HeartRateRecordEntity>,
        wakeDay: LocalDate,
        deviceName: String,
        stageLess: Boolean,
    ) {
        val bedTime =
            wakeDay
                .minusDays(1)
                .atTime(23, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val wakeTime =
            wakeDay
                .atTime(7, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val id = "sleep_${wakeDay}_main"

        if (stageLess) {
            // Mirrors today's HC-006 ingest behavior for a stage-less HC sleep session: duration
            // and efficiency derive only from stage minutes, so an empty stage list maps to zero.
            sleepSessions +=
                SleepSessionEntity(
                    id = id,
                    startTime = bedTime,
                    endTime = wakeTime,
                    durationMinutes = 0,
                    efficiency = 0f,
                    deepSleepMinutes = 0,
                    remSleepMinutes = 0,
                    lightSleepMinutes = 0,
                    awakeMinutes = 0,
                    deviceName = deviceName,
                )
            return
        }

        val deepMinutes = 90 + random.nextInt(30)
        val remMinutes = 90 + random.nextInt(30)
        val lightMinutes = 180 + random.nextInt(60)
        val awakeMinutes = 10 + random.nextInt(20)
        val totalMinutes = deepMinutes + remMinutes + lightMinutes + awakeMinutes

        sleepSessions +=
            SleepSessionEntity(
                id = id,
                startTime = bedTime,
                endTime = wakeTime,
                durationMinutes = deepMinutes + remMinutes + lightMinutes,
                efficiency = (deepMinutes + remMinutes + lightMinutes).toFloat() / totalMinutes * 100f,
                deepSleepMinutes = deepMinutes,
                remSleepMinutes = remMinutes,
                lightSleepMinutes = lightMinutes,
                awakeMinutes = awakeMinutes,
                deviceName = deviceName,
            )

        var stageStart = bedTime
        val order =
            listOf(
                "LIGHT" to lightMinutes / 2,
                "DEEP" to deepMinutes,
                "REM" to remMinutes,
                "LIGHT" to lightMinutes / 2,
            )
        for ((stageType, minutes) in order) {
            if (minutes <= 0) continue
            val stageEnd = stageStart + minutes * 60_000L
            sleepStages +=
                SleepStageEntity(
                    sessionId = id,
                    stageType = stageType,
                    startTime = stageStart,
                    endTime = stageEnd,
                    durationMinutes = minutes,
                )
            stageStart = stageEnd
        }

        // Overnight resting HR every 5 minutes -- covers HrCoverageValidator, sleep-HR-projection,
        // and the sleep-percentile RHR calculator.
        var sampleTime = bedTime
        var sampleIndex = 0
        while (sampleTime < wakeTime) {
            val bpm = 50 + (sampleIndex % 12) + random.nextInt(4)
            heartRateRows +=
                HeartRateRecordEntity(
                    id = "hr_${id}_$sampleIndex",
                    timestampMs = sampleTime,
                    beatsPerMinute = bpm,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = deviceName,
                )
            sampleTime += 5 * 60_000L
            sampleIndex++
        }
    }

    private fun addNap(
        sleepSessions: MutableList<SleepSessionEntity>,
        sleepStages: MutableList<SleepStageEntity>,
        heartRateRows: MutableList<HeartRateRecordEntity>,
        day: LocalDate,
        deviceName: String,
    ) {
        val napStart =
            day
                .atTime(14, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val napEnd =
            day
                .atTime(14, 45)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val id = "sleep_${day}_nap"
        sleepSessions +=
            SleepSessionEntity(
                id = id,
                startTime = napStart,
                endTime = napEnd,
                durationMinutes = 45,
                efficiency = 88f,
                deepSleepMinutes = 0,
                remSleepMinutes = 0,
                lightSleepMinutes = 45,
                awakeMinutes = 0,
                deviceName = deviceName,
            )
        sleepStages +=
            SleepStageEntity(
                sessionId = id,
                stageType = "LIGHT",
                startTime = napStart,
                endTime = napEnd,
                durationMinutes = 45,
            )
        var sampleTime = napStart
        var sampleIndex = 0
        while (sampleTime < napEnd) {
            heartRateRows +=
                HeartRateRecordEntity(
                    id = "hr_${id}_$sampleIndex",
                    timestampMs = sampleTime,
                    beatsPerMinute = 58 + random.nextInt(6),
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = deviceName,
                )
            sampleTime += 5 * 60_000L
            sampleIndex++
        }
    }

    private fun addNightlyHrv(
        hrvRows: MutableList<HrvRecordEntity>,
        wakeDay: LocalDate,
        deviceName: String,
    ) {
        val sampleTime =
            wakeDay
                .minusDays(1)
                .atTime(2, 30)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        hrvRows +=
            HrvRecordEntity(
                id = "hrv_$wakeDay",
                timestampMs = sampleTime,
                rmssdMs = 30f + random.nextInt(20),
                recordType = RecordType.RESTING.name,
                sessionId = null,
                deviceName = deviceName,
            )
    }

    private fun addDaytimeRestingHr(
        heartRateRows: MutableList<HeartRateRecordEntity>,
        day: LocalDate,
        deviceName: String,
    ) {
        // Sparse daytime coverage (every 15 minutes, 08:00-22:00) for the everyday-HR load path.
        var sampleTime =
            day
                .atTime(8, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val dayEnd =
            day
                .atTime(22, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        var sampleIndex = 0
        while (sampleTime < dayEnd) {
            heartRateRows +=
                HeartRateRecordEntity(
                    id = "hr_day_${day}_$sampleIndex",
                    timestampMs = sampleTime,
                    beatsPerMinute = 65 + random.nextInt(20),
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = deviceName,
                )
            sampleTime += 15 * 60_000L
            sampleIndex++
        }
    }

    private fun addWorkout(
        workoutRows: MutableList<WorkoutRecordEntity>,
        heartRateRows: MutableList<HeartRateRecordEntity>,
        day: LocalDate,
        deviceName: String,
        workoutSeq: Int,
    ) {
        val start =
            day
                .atTime(18, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        val durationMinutes = 30 + random.nextInt(30)
        val end = start + durationMinutes * 60_000L
        val id = "workout_$workoutSeq"

        var sampleTime = start
        var sampleIndex = 0
        var hrSum = 0
        var hrCount = 0
        while (sampleTime < end) {
            val bpm = 120 + random.nextInt(40)
            hrSum += bpm
            hrCount++
            heartRateRows +=
                HeartRateRecordEntity(
                    id = "hr_${id}_$sampleIndex",
                    timestampMs = sampleTime,
                    beatsPerMinute = bpm,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = deviceName,
                )
            sampleTime += 2 * 60_000L
            sampleIndex++
        }
        val avgHr = if (hrCount > 0) hrSum.toFloat() / hrCount else 130f

        workoutRows +=
            WorkoutRecordEntity(
                id = id,
                startTime = start,
                endTime = end,
                exerciseType = "RUNNING",
                durationMinutes = durationMinutes,
                zone1Minutes = durationMinutes * 0.1f,
                zone2Minutes = durationMinutes * 0.3f,
                zone3Minutes = durationMinutes * 0.4f,
                zone4Minutes = durationMinutes * 0.15f,
                zone5Minutes = durationMinutes * 0.05f,
                trimp = durationMinutes * 1.5f,
                avgHr = avgHr,
                deviceName = deviceName,
            )
    }
}
