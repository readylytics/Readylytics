package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import java.time.Clock
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
        private val daos: HealthRecordDaos,
        private val dailySummaryDao: DailySummaryDao,
        private val transactionRunner: TransactionRunner,
        private val vo2MaxRecordDao: Vo2MaxRecordDao,
        private val scanStagingDao: ScanStagingDao,
        private val sourcePayloadWriter: SourcePayloadWriter? = null,
        private val dirtyRangeStore: RoomDirtyRangeStore? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
        private val settingsRepo: SettingsRepository? = null,
        private val clock: Clock = Clock.systemDefaultZone(),
    ) : HealthIngestionStore {
        private val writer: SourcePayloadWriter =
            sourcePayloadWriter
                ?: SourcePayloadWriter(
                    daos = daos,
                    transactionRunner = transactionRunner,
                    dirtyRangeStore = dirtyRangeStore,
                    healthMutationStateDao = healthMutationStateDao,
                    settingsRepo = settingsRepo,
                    clock = clock,
                )
        override suspend fun persist(batch: HealthIngestionBatch) {
            // Persist parent and low-volume records first. Sample batches can then commit
            // independently; stable IDs make a retry of this window idempotent.
            transactionRunner.runInTransaction {
                daos.persistSleep(batch)
                daos.persistWorkouts(batch)
                daos.persistVitals(batch)
                daos.stepRecordDao.upsertAll(batch.stepRecords.map(StepRecordInput::toEntity))
                vo2MaxRecordDao.upsertAll(batch.vo2MaxSamples.map(Vo2MaxInput::toEntity))
            }
            persistHeartRate(batch)
            persistHrv(batch)
        }

        override suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            writer.replaceHeartRateSources(sources)
        }

        override suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) {
            writer.replaceHrvSources(sources)
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
            return daos.heartRateDao.countInRange(startMs, endMs)
        }

        override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int {
            return daos.hrvDao.countInRange(startMs, endMs)
        }

        override suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int {
            return daos.sleepSessionDao.countInRange(startMs, endMs)
        }

        override suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int {
            return daos.workoutDao.countInRange(startMs, endMs)
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
                val existing = daos.workoutDao.getById(workoutId)
                if (existing != null) {
                    val distanceMeters = totalDistanceMeters ?: existing.totalDistanceMeters
                    val derivedSpeed =
                        deriveWorkoutAvgSpeedKmh(distanceMeters, existing.startTime, existing.endTime)
                    daos.workoutDao.upsertAll(
                        listOf(
                            existing.copy(
                                routeState = routeState,
                                totalDistanceMeters = distanceMeters,
                                avgSpeedKmh = derivedSpeed ?: avgSpeedKmh ?: existing.avgSpeedKmh,
                                elevationGainMeters = elevationGainMeters ?: existing.elevationGainMeters,
                            ),
                        ),
                    )
                }
                daos.workoutRoutePointDao.deleteForWorkouts(listOf(workoutId))
                if (routePoints.isNotEmpty()) {
                    daos.workoutRoutePointDao.insertAll(routePoints.map(WorkoutRoutePoint::toEntity))
                }
            }
        }

        override suspend fun reconcileWindow(
            scan: CompleteTypeScan,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? =
            transactionRunner.runInTransaction {
                StagedDeletionReconciler.reconcile(
                    daos = daos,
                    vo2MaxRecordDao = vo2MaxRecordDao,
                    scanStagingDao = scanStagingDao,
                    scan = scan,
                    zoneId = zoneId,
                )
            }
    }

private suspend fun HealthRecordDaos.persistSleep(batch: HealthIngestionBatch) {
    sleepSessionDao.upsertAll(batch.sleepSessions.map(SleepSessionInput::toEntity))
    val sessionIds = batch.sleepSessions.map(SleepSessionInput::id).toSet()
    sleepStageDao.deleteForSessions(sessionIds.toList())
    sleepStageDao.upsertAll(
        batch.sleepStages
            .filter { it.sessionId in sessionIds }
            .map(SleepStageInput::toEntity),
    )
}

private suspend fun HealthRecordDaos.persistWorkouts(batch: HealthIngestionBatch) {
    val workoutEntities =
        batch.workouts.map { workout ->
            val existing = workoutDao.getById(workout.id)
            val fresh = workout.toEntity()
            val distanceMeters = fresh.totalDistanceMeters ?: existing?.totalDistanceMeters
            val avgSpeedKmh = deriveWorkoutAvgSpeedKmh(distanceMeters, workout.startTime, workout.endTime)
            fresh.copy(
                modelTrimp = existing?.modelTrimp,
                modelTrimpSourceRevision = existing?.modelTrimpSourceRevision,
                modelTrimpSnapshotId = existing?.modelTrimpSnapshotId,
                modelTrimpAlgorithmRevision = existing?.modelTrimpAlgorithmRevision,
                modelTrimpQuality = existing?.modelTrimpQuality,
                totalDistanceMeters = distanceMeters,
                avgSpeedKmh = avgSpeedKmh,
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
}

private suspend fun HealthRecordDaos.persistVitals(batch: HealthIngestionBatch) {
    val weightSourceIds = batch.weights.map { it.sourceId }.distinct()
    weightSourceIds.forEach { weightRecordDao.deleteBySourceRecordId(it) }
    weightRecordDao.upsertAll(batch.weights.map(WeightInput::toEntity))

    val bodyFatSourceIds = batch.bodyFatSamples.map { it.sourceId }.distinct()
    bodyFatSourceIds.forEach { bodyFatRecordDao.deleteBySourceRecordId(it) }
    bodyFatRecordDao.upsertAll(batch.bodyFatSamples.map(BodyFatInput::toEntity))

    val bpSourceIds = batch.bloodPressureSamples.map { it.sourceId }.distinct()
    bpSourceIds.forEach { bloodPressureRecordDao.deleteBySourceRecordId(it) }
    bloodPressureRecordDao.upsertAll(batch.bloodPressureSamples.map(BloodPressureInput::toEntity))

    val spo2SourceIds = batch.oxygenSaturationSamples.map { it.sourceId }.distinct()
    spo2SourceIds.forEach { oxygenSaturationRecordDao.deleteBySourceRecordId(it) }
    oxygenSaturationRecordDao.upsertAll(
        batch.oxygenSaturationSamples.map(OxygenSaturationInput::toEntity),
    )

    val tempSourceIds = batch.bodyTemperatureSamples.map { it.sourceId }.distinct()
    tempSourceIds.forEach { bodyTemperatureRecordDao.deleteBySourceRecordId(it) }
    bodyTemperatureRecordDao.upsertAll(
        batch.bodyTemperatureSamples.map(BodyTemperatureInput::toEntity),
    )
}

private suspend fun RoomHealthIngestionStore.persistHeartRate(batch: HealthIngestionBatch) {
    if (batch.heartRateSources.isNotEmpty()) {
        replaceHeartRateSources(batch.heartRateSources)
    } else if (batch.heartRateSamples.isNotEmpty()) {
        val grouped = batch.heartRateSamples.groupBy { it.sourceId }
        val payloads =
            grouped.map { (sourceId, samples) ->
                val minTs = samples.minOf { it.timestampMs }
                val maxTs = samples.maxOf { it.timestampMs }
                val meta =
                    SourceMetadata(
                        sourceId = sourceId,
                        recordType = "HEART_RATE",
                        originPackage = null,
                        startMs = minTs,
                        endExclusiveMs = maxTs + 1L,
                        lastModifiedMs = null,
                    )
                SourcePayload(meta, samples)
            }
        replaceHeartRateSources(payloads)
    }
}

private suspend fun RoomHealthIngestionStore.persistHrv(batch: HealthIngestionBatch) {
    if (batch.hrvSources.isNotEmpty()) {
        replaceHrvSources(batch.hrvSources)
    } else if (batch.hrvSamples.isNotEmpty()) {
        val grouped = batch.hrvSamples.groupBy { it.sourceId }
        val payloads =
            grouped.map { (sourceId, samples) ->
                val minTs = samples.minOf { it.timestampMs }
                val maxTs = samples.maxOf { it.timestampMs }
                val meta =
                    SourceMetadata(
                        sourceId = sourceId,
                        recordType = "HRV",
                        originPackage = null,
                        startMs = minTs,
                        endExclusiveMs = maxTs + 1L,
                        lastModifiedMs = null,
                    )
                SourcePayload(meta, samples)
            }
        replaceHrvSources(payloads)
    }
}



internal suspend fun <T> List<T>.forEachPersistenceBatch(
    batchSize: Int = 500,
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

