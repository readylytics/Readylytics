package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.RecordType
import java.time.LocalDate
import java.time.ZoneId

internal class ResidualFatigueScenarioSeeder(
    private val zoneId: ZoneId,
    private val historyStartDate: LocalDate,
) {
    data class ScenarioRecords(
        val workouts: MutableList<WorkoutRecordEntity> = mutableListOf(),
        val sourceRecords: MutableList<HealthSourceRecordEntity> = mutableListOf(),
        val heartRates: MutableList<HeartRateRecordEntity> = mutableListOf(),
        val hrvs: MutableList<HrvRecordEntity> = mutableListOf(),
        val sleepSessions: MutableList<SleepSessionEntity> = mutableListOf(),
    )

    suspend fun seedDeterministicScenario(database: HealthDatabase, seedEverydayAndHrv: Boolean = false) {
        val records = ScenarioRecords()

        seedEarlyWorkouts(records)
        seedLateWorkouts(records)
        seedScenarioSleepSessions(records, seedEverydayAndHrv)
        if (seedEverydayAndHrv) {
            seedEverydayHr(records)
        }

        database.sourceRecordDao().insertAll(records.sourceRecords)
        database.workoutDao().upsertAll(records.workouts)
        database.heartRateDao().upsertAll(records.heartRates)
        database.hrvDao().upsertAll(records.hrvs)
        database.sleepSessionDao().upsertAll(records.sleepSessions)

        val reconciler = SessionLinkReconcilerImpl(
            sleepSessionDao = database.sleepSessionDao(),
            workoutDao = database.workoutDao(),
            heartRateDao = database.heartRateDao(),
            hrvDao = database.hrvDao(),
            transactionRunner = RoomTransactionRunner(database),
        )
        val zoneThresholds = ZoneThresholds.create(90, 110, 130, 150, 170)
        reconciler.reconcile(
            epoch(historyStartDate, 0, 0),
            epoch(LocalDate.of(2026, 6, 7), 0, 0),
            zoneThresholds,
        )
    }

    private fun seedEarlyWorkouts(records: ScenarioRecords) {
        addScenarioWorkout(
            records, "workout-older-32d",
            epoch(LocalDate.of(2026, 4, 15), 10, 0),
            epoch(LocalDate.of(2026, 4, 15), 11, 0),
            45f, 155f,
        )
        addScenarioWorkout(
            records, "workout-ordinary",
            epoch(LocalDate.of(2026, 5, 20), 14, 0),
            epoch(LocalDate.of(2026, 5, 20), 15, 0),
            35f, 150f,
        )
        addScenarioWorkout(
            records, "workout-midnight",
            epoch(LocalDate.of(2026, 5, 22), 23, 30),
            epoch(LocalDate.of(2026, 5, 23), 0, 30),
            40f, 160f,
        )
        addScenarioWorkout(
            records, "workout-consecutive-1",
            epoch(LocalDate.of(2026, 5, 24), 10, 0),
            epoch(LocalDate.of(2026, 5, 24), 11, 0),
            30f, 145f,
        )
        addScenarioWorkout(
            records, "workout-consecutive-2",
            epoch(LocalDate.of(2026, 5, 25), 10, 0),
            epoch(LocalDate.of(2026, 5, 25), 11, 0),
            35f, 150f,
        )
        addScenarioWorkout(
            records, "workout-consecutive-3",
            epoch(LocalDate.of(2026, 5, 26), 10, 0),
            epoch(LocalDate.of(2026, 5, 26), 11, 0),
            40f, 155f,
        )
    }

    private fun seedLateWorkouts(records: ScenarioRecords) {
        addScenarioWorkout(
            records, "workout-early",
            epoch(LocalDate.of(2026, 5, 27), 6, 30),
            epoch(LocalDate.of(2026, 5, 27), 7, 30),
            25f, 140f,
        )
        addScenarioWorkout(
            records, "workout-late",
            epoch(LocalDate.of(2026, 5, 27), 20, 0),
            epoch(LocalDate.of(2026, 5, 27), 21, 0),
            30f, 150f,
        )
        addScenarioWorkout(
            records, "workout-tied-a",
            epoch(LocalDate.of(2026, 5, 29), 9, 30),
            epoch(LocalDate.of(2026, 5, 29), 10, 30),
            20f, 140f,
        )
        addScenarioWorkout(
            records, "workout-tied-b",
            epoch(LocalDate.of(2026, 5, 29), 8, 30),
            epoch(LocalDate.of(2026, 5, 29), 10, 30),
            50f, 165f,
        )
        addScenarioWorkout(
            records, "workout-zero-trimp",
            epoch(LocalDate.of(2026, 5, 30), 14, 0),
            epoch(LocalDate.of(2026, 5, 30), 14, 30),
            0f, 80f,
        )
        addScenarioWorkout(
            records, "workout-future",
            epoch(LocalDate.of(2026, 6, 5), 10, 0),
            epoch(LocalDate.of(2026, 6, 5), 11, 0),
            45f, 155f,
        )
    }

    private fun seedEverydayHr(records: ScenarioRecords) {
        val everydayHrTime = epoch(historyStartDate.plusDays(40), 12, 0)
        val everydayRef = 99999L
        for (i in 0..10) {
            val time = everydayHrTime + i * 300_000L
            records.sourceRecords.add(
                HealthSourceRecordEntity(
                    id = everydayRef + i,
                    sourceRecordId = "source-${everydayRef + i}",
                    recordType = "HeartRateRecord",
                    createdAtMs = time,
                )
            )
            records.heartRates.add(
                HeartRateRecordEntity(
                    sourceRecordRef = everydayRef + i,
                    timestampMs = time,
                    beatsPerMinute = 160,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                )
            )
        }
    }

    private fun addScenarioWorkout(
        records: ScenarioRecords,
        id: String,
        startEpochMs: Long,
        endEpochMs: Long,
        trimp: Float,
        avgHr: Float,
    ) {
        val durationMinutes = ((endEpochMs - startEpochMs) / 60_000L).toInt()
        records.workouts.add(
            WorkoutRecordEntity(
                id = id,
                startTime = startEpochMs,
                endTime = endEpochMs,
                exerciseType = "RUNNING",
                durationMinutes = durationMinutes,
                zone1Minutes = (durationMinutes * 0.2f),
                zone2Minutes = (durationMinutes * 0.3f),
                zone3Minutes = (durationMinutes * 0.3f),
                zone4Minutes = (durationMinutes * 0.2f),
                zone5Minutes = 0f,
                trimp = trimp,
                avgHr = avgHr,
                modelTrimp = null,
            ),
        )
        var t = startEpochMs
        var ref = 1000L + records.workouts.size * 100L
        while (t < endEpochMs) {
            val currentRef = ++ref
            records.sourceRecords.add(
                HealthSourceRecordEntity(
                    id = currentRef,
                    sourceRecordId = "source-$currentRef",
                    recordType = "HeartRateRecord",
                    createdAtMs = t,
                ),
            )
            records.heartRates.add(
                HeartRateRecordEntity(
                    sourceRecordRef = currentRef,
                    timestampMs = t,
                    beatsPerMinute = avgHr.toInt(),
                    recordType = RecordType.EXERCISE.name,
                    sessionId = id,
                ),
            )
            t += 60_000L
        }
    }

    private fun seedScenarioSleepSessions(records: ScenarioRecords, seedEverydayAndHrv: Boolean) {
        var d = historyStartDate
        while (!d.isAfter(LocalDate.of(2026, 6, 6))) {
            val sleepStart = epoch(d, 23, 0)
            val sleepId = "sleep-$d"
            addScenarioSleepSession(records, d, sleepId, sleepStart)
            addScenarioSleepHeartRate(records, sleepId, sleepStart)
            if (seedEverydayAndHrv) {
                addScenarioSleepHrv(records, sleepId, sleepStart)
            }
            d = d.plusDays(1)
        }
    }

    private fun addScenarioSleepSession(
        records: ScenarioRecords,
        day: LocalDate,
        sleepId: String,
        sleepStart: Long,
    ) {
        val sleepEnd = epoch(day.plusDays(1), 7, 0)
        records.sleepSessions.add(
            SleepSessionEntity(
                id = sleepId,
                startTime = sleepStart,
                endTime = sleepEnd,
                durationMinutes = 480,
                efficiency = 0.92f,
                deepSleepMinutes = 90,
                remSleepMinutes = 110,
                lightSleepMinutes = 240,
                awakeMinutes = 40,
            ),
        )
    }

    private fun addScenarioSleepHeartRate(records: ScenarioRecords, sleepId: String, sleepStart: Long) {
        val sleepRef = 50000L + records.sleepSessions.size
        records.sourceRecords.add(
            HealthSourceRecordEntity(
                id = sleepRef,
                sourceRecordId = "source-$sleepRef",
                recordType = "HeartRateRecord",
                createdAtMs = sleepStart,
            ),
        )
        records.heartRates.add(
            HeartRateRecordEntity(
                sourceRecordRef = sleepRef,
                timestampMs = sleepStart + 4 * 3_600_000L,
                beatsPerMinute = 56,
                recordType = RecordType.SLEEP.name,
                sessionId = sleepId,
            ),
        )
    }

    private fun addScenarioSleepHrv(records: ScenarioRecords, sleepId: String, sleepStart: Long) {
        val hrvRef = 60000L + records.sleepSessions.size
        records.sourceRecords.add(
            HealthSourceRecordEntity(
                id = hrvRef,
                sourceRecordId = "source-hrv-$hrvRef",
                recordType = "HeartRateVariabilityRmssdRecord",
                createdAtMs = sleepStart,
            ),
        )
        records.hrvs.add(
            HrvRecordEntity(
                sourceRecordRef = hrvRef,
                timestampMs = sleepStart + 4 * 3_600_000L,
                rmssdMs = 65f,
                recordType = "HeartRateVariabilityRmssdRecord",
                sessionId = sleepId,
            ),
        )
    }

    private fun epoch(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}
