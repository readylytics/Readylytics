package app.readylytics.health.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.domain.model.WorkoutRoutePoint
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.BloodPressureInput
import app.readylytics.health.domain.sync.BodyFatInput
import app.readylytics.health.domain.sync.BodyTemperatureInput
import app.readylytics.health.domain.sync.HealthIngestionBatch
import app.readylytics.health.domain.sync.HealthIngestionStore
import app.readylytics.health.domain.sync.HeartRateInput
import app.readylytics.health.domain.sync.HrvInput
import app.readylytics.health.domain.sync.OxygenSaturationInput
import app.readylytics.health.domain.sync.SleepSessionInput
import app.readylytics.health.domain.sync.SleepStageInput
import app.readylytics.health.domain.sync.StepRecordInput
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
        private val workoutRoutePointDao: WorkoutRoutePointDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val bodyTemperatureRecordDao: BodyTemperatureRecordDao,
        private val stepRecordDao: StepRecordDao,
        private val dailySummaryDao: DailySummaryDao,
        private val sourceRecordDao: SourceRecordDao,
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
                // A pass that failed to read routes (transient RemoteException/IO error, revoked
                // route consent) reports NOT_AVAILABLE with an empty point list. Overwriting on
                // that would wipe previously ingested GPS data, which breaks the ingestion
                // idempotency contract -- so the GPS columns and route points are only replaced
                // when this pass actually produced a route. Mirrors persistSingleWorkoutRoute.
                val workoutEntities =
                    batch.workouts.map { workout ->
                        val existing = workoutDao.getById(workout.id)
                        val fresh = workout.toEntity()
                        fresh.copy(
                            modelTrimp = existing?.modelTrimp,
                            totalDistanceMeters = fresh.totalDistanceMeters ?: existing?.totalDistanceMeters,
                            avgSpeedKmh = fresh.avgSpeedKmh ?: existing?.avgSpeedKmh,
                            elevationGainMeters = fresh.elevationGainMeters ?: existing?.elevationGainMeters,
                            routeState =
                                if (workout.routePoints.isEmpty() && existing?.routeState == RouteState.IMPORTED) {
                                    existing.routeState
                                } else {
                                    fresh.routeState
                                },
                        )
                    }
                workoutDao.upsertAll(workoutEntities)
                val workoutsWithRoutes = batch.workouts.filter { it.routePoints.isNotEmpty() }
                if (workoutsWithRoutes.isNotEmpty()) {
                    workoutRoutePointDao.deleteForWorkouts(workoutsWithRoutes.map(WorkoutInput::id))
                    workoutRoutePointDao.insertAll(
                        workoutsWithRoutes.flatMap { workout ->
                            workout.routePoints.map(WorkoutRoutePoint::toEntity)
                        },
                    )
                }
                weightRecordDao.upsertAll(batch.weights.map(WeightInput::toEntity))
                bodyFatRecordDao.upsertAll(batch.bodyFatSamples.map(BodyFatInput::toEntity))
                bloodPressureRecordDao.upsertAll(batch.bloodPressureSamples.map(BloodPressureInput::toEntity))
                oxygenSaturationRecordDao.upsertAll(
                    batch.oxygenSaturationSamples.map(OxygenSaturationInput::toEntity),
                )
                bodyTemperatureRecordDao.upsertAll(
                    batch.bodyTemperatureSamples.map(BodyTemperatureInput::toEntity),
                )
                stepRecordDao.upsertAll(batch.stepRecords.map(StepRecordInput::toEntity))
            }

            persistHeartRateSamples(batch.heartRateSamples)
            persistHrvSamples(batch.hrvSamples)
        }

        override suspend fun persistHeartRateSamples(samples: List<HeartRateInput>) {
            if (samples.isEmpty()) return
            val sourceRefByBaseId = samples.mapTo(mutableSetOf()) { it.id.substringBefore('_') }
                .associateWith { baseId ->
                    sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = baseId,
                        recordType = "HEART_RATE",
                        createdAtMs = samples.first().timestampMs,
                    )
                }
            samples.forEachPersistenceBatch { batch ->
                val startedAt = System.currentTimeMillis()
                transactionRunner.runInTransaction {
                    heartRateDao.upsertAll(batch.map { input -> input.toEntity(sourceRefByBaseId) })
                }
                logD(PERSIST_TAG) {
                    "HR batch persisted: ${batch.size} samples in ${System.currentTimeMillis() - startedAt}ms"
                }
            }
        }

        override suspend fun persistHrvSamples(samples: List<HrvInput>) {
            if (samples.isEmpty()) return
            val sourceRefByBaseId = samples.mapTo(mutableSetOf()) { it.id.substringBefore('_') }
                .associateWith { baseId ->
                    sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = baseId,
                        recordType = "HRV",
                        createdAtMs = samples.first().timestampMs,
                    )
                }
            samples.forEachPersistenceBatch { batch ->
                val startedAt = System.currentTimeMillis()
                transactionRunner.runInTransaction {
                    hrvDao.upsertAll(batch.map { input -> input.toEntity(sourceRefByBaseId) })
                }
                logD(PERSIST_TAG) {
                    "HRV batch persisted: ${batch.size} samples in ${System.currentTimeMillis() - startedAt}ms"
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

        override suspend fun persistSingleWorkoutRoute(
            workoutId: String,
            routePoints: List<WorkoutRoutePoint>,
            routeState: String,
            totalDistanceMeters: Float?,
            avgSpeedKmh: Float?,
            elevationGainMeters: Float?,
        ) {
            transactionRunner.runInTransaction {
                val existing = workoutDao.getById(workoutId)
                if (existing != null) {
                    workoutDao.upsertAll(
                        listOf(
                            existing.copy(
                                routeState = routeState,
                                totalDistanceMeters = totalDistanceMeters ?: existing.totalDistanceMeters,
                                avgSpeedKmh = avgSpeedKmh ?: existing.avgSpeedKmh,
                                elevationGainMeters = elevationGainMeters ?: existing.elevationGainMeters,
                            ),
                        ),
                    )
                }
                workoutRoutePointDao.deleteForWorkouts(listOf(workoutId))
                if (routePoints.isNotEmpty()) {
                    workoutRoutePointDao.insertAll(routePoints.map(WorkoutRoutePoint::toEntity))
                }
            }
        }
    }

private const val TAG = "RoomHealthIngestionStore"
private const val PERSIST_TAG = "HealthSync.Persist"

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

private fun HeartRateInput.toEntity(sourceRefByBaseId: Map<String, Long>) =
    HeartRateRecordEntity(
        sourceRecordRef = sourceRefByBaseId.getValue(id.substringBefore('_')),
        timestampMs = timestampMs,
        beatsPerMinute = beatsPerMinute,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

private fun HrvInput.toEntity(sourceRefByBaseId: Map<String, Long>) =
    HrvRecordEntity(
        sourceRecordRef = sourceRefByBaseId.getValue(id.substringBefore('_')),
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
        totalDistanceMeters = totalDistanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
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

private fun BodyTemperatureInput.toEntity() =
    BodyTemperatureRecordEntity(
        id = id,
        timestampMs = timestampMs,
        celsius = celsius,
        deviceName = deviceName,
    )

private fun StepRecordInput.toEntity() =
    StepRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        count = count,
        deviceName = deviceName,
    )

private fun WorkoutRoutePoint.toEntity() =
    WorkoutRoutePointEntity(
        id = id,
        workoutId = workoutId,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        timestampMs = timestampMs,
        horizontalAccuracy = horizontalAccuracy,
        verticalAccuracy = verticalAccuracy,
    )
