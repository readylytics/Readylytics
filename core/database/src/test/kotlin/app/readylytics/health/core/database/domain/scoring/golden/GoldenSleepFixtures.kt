package app.readylytics.health.core.database.domain.scoring.golden

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import java.time.LocalDate
import java.time.ZoneId

internal class GoldenSleepFixtures(
    private val db: HealthDatabase,
    private val targetDate: LocalDate,
    private val zoneId: ZoneId,
) {
    suspend fun seedCalibratedHistory(days: Int = 14) {
        val sessions = (1..days).map(::historySession)
        val sources =
            (1..days).map { index ->
                HealthSourceRecordEntity(
                    id = index.toLong(),
                    sourceRecordId = "hist_source_$index",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            }
        val hrSamples =
            sessions.flatMapIndexed { index, session ->
                (0..48).map { step ->
                    HeartRateRecordEntity(
                        sourceRecordRef = index + 1L,
                        timestampMs = session.startTime + step * 10 * 60_000L,
                        beatsPerMinute = 52 + (step % 6),
                        recordType = "SLEEP",
                        sessionId = session.id,
                        deviceName = "Pixel",
                    )
                }
            }
        val hrvSamples =
            sessions.mapIndexed { index, session ->
                HrvRecordEntity(
                    sourceRecordRef = index + 1L,
                    timestampMs = session.startTime + 3600_000L,
                    rmssdMs = 55f + ((index + 1) % 5),
                    recordType = "SLEEP",
                    sessionId = session.id,
                    deviceName = "Pixel",
                )
            }
        db.sourceRecordDao().insertAll(sources)
        db.sleepSessionDao().upsertAll(sessions)
        db.heartRateDao().upsertAll(hrSamples)
        db.hrvDao().upsertAll(hrvSamples)
    }

    private fun historySession(index: Int): SleepSessionEntity {
        val date = targetDate.minusDays(index.toLong())
        return SleepSessionEntity(
            id = "hist_sleep_$index",
            startTime =
                date
                    .atTime(23, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli(),
            endTime =
                date
                    .plusDays(1)
                    .atTime(7, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli(),
            durationMinutes = 480,
            efficiency = 90f,
            deepSleepMinutes = 90,
            remSleepMinutes = 90,
            lightSleepMinutes = 270,
            awakeMinutes = 30,
            deviceName = "Pixel",
        )
    }

    suspend fun seedMidnightSleepStages(
        sessionId: String,
        sleepStart: Long,
        sleepEnd: Long,
    ) {
        db.sleepStageDao().upsertAll(
            listOf(
                SleepStageEntity(
                    sessionId = sessionId,
                    startTime = sleepStart,
                    endTime = sleepStart + 90 * 60000L,
                    stageType = "LIGHT",
                    durationMinutes = 90,
                ),
                SleepStageEntity(
                    sessionId = sessionId,
                    startTime = sleepStart + 90 * 60000L,
                    endTime = sleepStart + 195 * 60000L,
                    stageType = "DEEP",
                    durationMinutes = 105,
                ),
                SleepStageEntity(
                    sessionId = sessionId,
                    startTime = sleepStart + 195 * 60000L,
                    endTime = sleepStart + 290 * 60000L,
                    stageType = "REM",
                    durationMinutes = 95,
                ),
                SleepStageEntity(
                    sessionId = sessionId,
                    startTime = sleepStart + 290 * 60000L,
                    endTime = sleepEnd,
                    stageType = "LIGHT",
                    durationMinutes = 115,
                ),
            ),
        )
    }

    suspend fun seedMidnightSleepHr(
        sessionId: String,
        sleepStart: Long,
    ) {
        val sourceRef = 2000L
        db.sourceRecordDao().insertAll(
            listOf(
                HealthSourceRecordEntity(
                    id = sourceRef,
                    sourceRecordId = "case2_sleep_source",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                ),
            ),
        )
        val hrSamples =
            (0..49).map { step ->
                HeartRateRecordEntity(
                    sourceRecordRef = sourceRef,
                    timestampMs = sleepStart + step * 10 * 60_000L,
                    beatsPerMinute = 50 + (step % 5),
                    recordType = "SLEEP",
                    sessionId = sessionId,
                    deviceName = "Pixel",
                )
            }
        db.heartRateDao().upsertAll(hrSamples)
        db.hrvDao().upsertAll(
            listOf(
                HrvRecordEntity(
                    sourceRecordRef = sourceRef,
                    timestampMs = sleepStart + 2 * 3600_000L,
                    rmssdMs = 62f,
                    recordType = "SLEEP",
                    sessionId = sessionId,
                    deviceName = "Pixel",
                ),
            ),
        )
    }
}
