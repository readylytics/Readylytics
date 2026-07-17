package app.readylytics.health.data.local

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.SleepStageDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.SleepStageEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.BloodPressureInput
import app.readylytics.health.domain.sync.BodyFatInput
import app.readylytics.health.domain.sync.HealthIngestionBatch
import app.readylytics.health.domain.sync.HealthIngestionStore
import app.readylytics.health.domain.sync.HeartRateInput
import app.readylytics.health.domain.sync.HrvInput
import app.readylytics.health.domain.sync.OxygenSaturationInput
import app.readylytics.health.domain.sync.SleepSessionInput
import app.readylytics.health.domain.sync.SleepStageInput
import app.readylytics.health.domain.sync.WeightInput
import app.readylytics.health.domain.sync.WorkoutInput
import app.readylytics.health.domain.util.logD
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

@Singleton
class RoomHealthIngestionStore
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val sleepStageDao: SleepStageDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val dailySummaryDao: DailySummaryDao,
        private val transactionRunner: TransactionRunner,
    ) : HealthIngestionStore {
        override suspend fun persist(batch: HealthIngestionBatch) {
            // Persist parent and low-volume records first. Sample batches can then commit
            // independently; stable IDs make a retry of this window idempotent.
            transactionRunner.runInTransaction {
                sleepSessionDao.upsertAll(batch.sleepSessions.map(SleepSessionInput::toEntity))
                val sessionIds = batch.sleepSessions.map(SleepSessionInput::id).toSet()
                sleepStageDao.deleteForSessions(sessionIds.toList())
                sleepStageDao.upsertAll(
                    batch.sleepStages
                        .filter { it.sessionId in sessionIds }
                        .map(SleepStageInput::toEntity),
                )
                workoutDao.upsertAll(batch.workouts.map(WorkoutInput::toEntity))
                weightRecordDao.upsertAll(batch.weights.map(WeightInput::toEntity))
                bodyFatRecordDao.upsertAll(batch.bodyFatSamples.map(BodyFatInput::toEntity))
                bloodPressureRecordDao.upsertAll(batch.bloodPressureSamples.map(BloodPressureInput::toEntity))
                oxygenSaturationRecordDao.upsertAll(
                    batch.oxygenSaturationSamples.map(OxygenSaturationInput::toEntity),
                )
            }

            var persistedHeartRateSamples = 0
            batch.heartRateSamples.forEachPersistenceBatch { samples ->
                val startedAt = System.currentTimeMillis()
                transactionRunner.runInTransaction {
                    heartRateDao.upsertAll(samples.map(HeartRateInput::toEntity))
                }
                persistedHeartRateSamples += samples.size
                logD(TAG) {
                    "Persisted HR batch: $persistedHeartRateSamples/${batch.heartRateSamples.size} " +
                        "samples in ${System.currentTimeMillis() - startedAt}ms"
                }
            }
            var persistedHrvSamples = 0
            batch.hrvSamples.forEachPersistenceBatch { samples ->
                val startedAt = System.currentTimeMillis()
                transactionRunner.runInTransaction {
                    hrvDao.upsertAll(samples.map(HrvInput::toEntity))
                }
                persistedHrvSamples += samples.size
                logD(TAG) {
                    "Persisted HRV batch: $persistedHrvSamples/${batch.hrvSamples.size} " +
                        "samples in ${System.currentTimeMillis() - startedAt}ms"
                }
            }
        }

        override suspend fun clearFrozenBaselines(
            start: java.time.LocalDate,
            endExclusive: java.time.LocalDate,
            zoneId: ZoneId,
        ) {
            dailySummaryDao.clearFrozenBaselinesBetween(
                fromMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                toExclusiveMs = endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            )
        }

        override suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int {
            return heartRateDao.countInRange(startMs, endMs)
        }

        override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int {
            return hrvDao.countInRange(startMs, endMs)
        }

        override suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int {
            return sleepSessionDao.countInRange(startMs, endMs)
        }

        override suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int {
            return workoutDao.countInRange(startMs, endMs)
        }
    }

private const val TAG = "RoomHealthIngestionStore"

internal suspend fun <T> List<T>.forEachPersistenceBatch(
    batchSize: Int = 5_000,
    action: suspend (List<T>) -> Unit,
) {
    require(batchSize > 0) { "batchSize must be positive" }
    var start = 0
    while (start < size) {
        currentCoroutineContext().ensureActive()
        action(subList(start, minOf(start + batchSize, size)))
        start += batchSize
        yield()
    }
}

private fun SleepSessionInput.toEntity() =
    SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        deviceName = deviceName,
    )

private fun SleepStageInput.toEntity() =
    SleepStageEntity(
        sessionId = sessionId,
        stageType = stageType,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
    )

private fun HeartRateInput.toEntity() =
    HeartRateRecordEntity(
        id = id,
        timestampMs = timestampMs,
        beatsPerMinute = beatsPerMinute,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

private fun HrvInput.toEntity() =
    HrvRecordEntity(
        id = id,
        timestampMs = timestampMs,
        rmssdMs = rmssdMs,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

private fun WorkoutInput.toEntity() =
    WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = exerciseType,
        durationMinutes = durationMinutes,
        zone1Minutes = zone1Minutes,
        zone2Minutes = zone2Minutes,
        zone3Minutes = zone3Minutes,
        zone4Minutes = zone4Minutes,
        zone5Minutes = zone5Minutes,
        trimp = trimp,
        avgHr = avgHr,
        deviceName = deviceName,
    )

private fun WeightInput.toEntity() =
    WeightRecordEntity(
        id = id,
        timestampMs = timestampMs,
        weightKg = weightKg,
        deviceName = deviceName,
    )

private fun BodyFatInput.toEntity() =
    BodyFatRecordEntity(
        id = id,
        timestampMs = timestampMs,
        bodyFatPercent = bodyFatPercent,
        deviceName = deviceName,
    )

private fun BloodPressureInput.toEntity() =
    BloodPressureRecordEntity(
        id = id,
        timestampMs = timestampMs,
        systolicMmHg = systolicMmHg,
        diastolicMmHg = diastolicMmHg,
        deviceName = deviceName,
    )

private fun OxygenSaturationInput.toEntity() =
    OxygenSaturationRecordEntity(
        id = id,
        timestampMs = timestampMs,
        percentage = percentage,
        deviceName = deviceName,
    )
