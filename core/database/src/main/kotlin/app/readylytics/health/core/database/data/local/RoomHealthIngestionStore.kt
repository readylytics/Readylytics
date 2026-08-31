package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.util.logD
import java.time.Instant
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
    ) : HealthIngestionStore {
        override suspend fun persist(batch: HealthIngestionBatch) {
            // Persist parent and low-volume records first. Sample batches can then commit
            // independently; stable IDs make a retry of this window idempotent.
            transactionRunner.runInTransaction {
                daos.sleepSessionDao.upsertAll(batch.sleepSessions.map(SleepSessionInput::toEntity))
                val sessionIds = batch.sleepSessions.map(SleepSessionInput::id).toSet()
                daos.sleepStageDao.deleteForSessions(sessionIds.toList())
                daos.sleepStageDao.upsertAll(
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
                        val existing = daos.workoutDao.getById(workout.id)
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
                daos.workoutDao.upsertAll(workoutEntities)
                val workoutsWithRoutes = batch.workouts.filter { it.routePoints.isNotEmpty() }
                if (workoutsWithRoutes.isNotEmpty()) {
                    daos.workoutRoutePointDao.deleteForWorkouts(workoutsWithRoutes.map(WorkoutInput::id))
                    daos.workoutRoutePointDao.insertAll(
                        workoutsWithRoutes.flatMap { workout ->
                            workout.routePoints.map(WorkoutRoutePoint::toEntity)
                        },
                    )
                }
                daos.weightRecordDao.upsertAll(batch.weights.map(WeightInput::toEntity))
                daos.bodyFatRecordDao.upsertAll(batch.bodyFatSamples.map(BodyFatInput::toEntity))
                daos.bloodPressureRecordDao.upsertAll(batch.bloodPressureSamples.map(BloodPressureInput::toEntity))
                daos.oxygenSaturationRecordDao.upsertAll(
                    batch.oxygenSaturationSamples.map(OxygenSaturationInput::toEntity),
                )
                daos.bodyTemperatureRecordDao.upsertAll(
                    batch.bodyTemperatureSamples.map(BodyTemperatureInput::toEntity),
                )
                daos.stepRecordDao.upsertAll(batch.stepRecords.map(StepRecordInput::toEntity))
            }

            persistHeartRateSamples(batch.heartRateSamples)
            persistHrvSamples(batch.hrvSamples)
        }

        override suspend fun persistHeartRateSamples(samples: List<HeartRateInput>) {
            if (samples.isEmpty()) return
            val sourceRefByBaseId = samples.mapTo(mutableSetOf()) { it.id.substringBefore('_') }
                .associateWith { baseId ->
                    daos.sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = baseId,
                        recordType = "HEART_RATE",
                        createdAtMs = samples.first().timestampMs,
                    )
                }
            samples.forEachPersistenceBatch { batch ->
                val startedAt = System.currentTimeMillis()
                transactionRunner.runInTransaction {
                    daos.heartRateDao.upsertAll(batch.map { input -> input.toEntity(sourceRefByBaseId) })
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
                    daos.sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = baseId,
                        recordType = "HRV",
                        createdAtMs = samples.first().timestampMs,
                    )
                }
            samples.forEachPersistenceBatch { batch ->
                val startedAt = System.currentTimeMillis()
                transactionRunner.runInTransaction {
                    daos.hrvDao.upsertAll(batch.map { input -> input.toEntity(sourceRefByBaseId) })
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
                    daos.workoutDao.upsertAll(
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
                daos.workoutRoutePointDao.deleteForWorkouts(listOf(workoutId))
                if (routePoints.isNotEmpty()) {
                    daos.workoutRoutePointDao.insertAll(routePoints.map(WorkoutRoutePoint::toEntity))
                }
            }
        }

        override suspend fun reconcileWindow(
            type: HealthDataType,
            windowStartMs: Long,
            windowEndMs: Long,
            hcIds: Set<String>,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? =
            transactionRunner.runInTransaction {
                HealthRecordDeletionReconciler.reconcile(
                    daos = daos,
                    type = type,
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                    hcIds = hcIds,
                    zoneId = zoneId,
                )
            }
    }

internal data class ReconcileContext(
    val startMs: Long,
    val endMs: Long,
    val hcIds: Set<String>,
    val zoneId: ZoneId,
)

internal object HealthRecordDeletionReconciler {
    suspend fun reconcile(
        daos: HealthRecordDaos,
        type: HealthDataType,
        windowStartMs: Long,
        windowEndMs: Long,
        hcIds: Set<String>,
        zoneId: ZoneId,
    ): ScoreInvalidation.AffectedRange? {
        val context = ReconcileContext(windowStartMs, windowEndMs, hcIds, zoneId)
        return when (type) {
            HealthDataType.SLEEP -> reconcileSleep(daos, context)
            HealthDataType.EXERCISE -> reconcileExercise(daos, context)
            HealthDataType.HEART_RATE -> reconcileHeartSource(daos, "HEART_RATE", context)
            HealthDataType.HRV -> reconcileHeartSource(daos, "HRV", context)
            HealthDataType.STEPS -> reconcileSteps(daos, context)
            else -> reconcileVitals(daos, type, context)
        }
    }

    private suspend fun reconcileVitals(
        daos: HealthRecordDaos,
        type: HealthDataType,
        ctx: ReconcileContext,
    ): ScoreInvalidation.AffectedRange? =
        when (type) {
            HealthDataType.WEIGHT ->
                reconcileCompositeMetric(
                    ctx = ctx,
                    fetch = { start, end -> daos.weightRecordDao.getByTimeRange(start, end) },
                    getId = { it.id },
                    getTimestamp = { it.timestampMs },
                    deleteById = { daos.weightRecordDao.deleteById(it) },
                    deleteBetween = { start, end -> daos.weightRecordDao.deleteBetween(start, end) },
                )
            HealthDataType.BODY_FAT ->
                reconcileCompositeMetric(
                    ctx = ctx,
                    fetch = { start, end -> daos.bodyFatRecordDao.getByTimeRange(start, end) },
                    getId = { it.id },
                    getTimestamp = { it.timestampMs },
                    deleteById = { daos.bodyFatRecordDao.deleteById(it) },
                    deleteBetween = { start, end -> daos.bodyFatRecordDao.deleteBetween(start, end) },
                )
            HealthDataType.BLOOD_PRESSURE ->
                reconcileCompositeMetric(
                    ctx = ctx,
                    fetch = { start, end -> daos.bloodPressureRecordDao.getBetween(start, end) },
                    getId = { it.id },
                    getTimestamp = { it.timestampMs },
                    deleteById = { daos.bloodPressureRecordDao.deleteById(it) },
                    deleteBetween = { start, end -> daos.bloodPressureRecordDao.deleteBetween(start, end) },
                )
            HealthDataType.OXYGEN_SATURATION ->
                reconcileCompositeMetric(
                    ctx = ctx,
                    fetch = { start, end -> daos.oxygenSaturationRecordDao.getByTimeRange(start, end) },
                    getId = { it.id },
                    getTimestamp = { it.timestampMs },
                    deleteById = { daos.oxygenSaturationRecordDao.deleteById(it) },
                    deleteBetween = { start, end -> daos.oxygenSaturationRecordDao.deleteBetween(start, end) },
                )
            HealthDataType.BODY_TEMPERATURE ->
                reconcileCompositeMetric(
                    ctx = ctx,
                    fetch = { start, end -> daos.bodyTemperatureRecordDao.getByTimeRange(start, end) },
                    getId = { it.id },
                    getTimestamp = { it.timestampMs },
                    deleteById = { daos.bodyTemperatureRecordDao.deleteById(it) },
                    deleteBetween = { start, end -> daos.bodyTemperatureRecordDao.deleteBetween(start, end) },
                )
            else -> null
        }

    private suspend fun reconcileSleep(
        daos: HealthRecordDaos,
        ctx: ReconcileContext,
    ): ScoreInvalidation.AffectedRange? {
        val localSessions = daos.sleepSessionDao.getBetween(ctx.startMs, ctx.endMs)
        val toDelete = localSessions.filter { it.id !in ctx.hcIds }
        if (toDelete.isEmpty()) return null

        val idsToDelete = toDelete.map { it.id }
        daos.sleepStageDao.deleteForSessions(idsToDelete)
        if (ctx.hcIds.isEmpty()) {
            daos.sleepSessionDao.deleteBetween(ctx.startMs, ctx.endMs)
        } else {
            daos.sleepSessionDao.deleteSessionsNotIn(ctx.startMs, ctx.endMs, ctx.hcIds.toList())
        }
        return toAffectedRange(toDelete.minOf { it.startTime }, toDelete.maxOf { it.endTime }, ctx.zoneId)
    }

    private suspend fun reconcileExercise(
        daos: HealthRecordDaos,
        ctx: ReconcileContext,
    ): ScoreInvalidation.AffectedRange? {
        val localWorkouts = daos.workoutDao.getBetween(ctx.startMs, ctx.endMs)
        val toDelete = localWorkouts.filter { it.id !in ctx.hcIds }
        if (toDelete.isEmpty()) return null

        val idsToDelete = toDelete.map { it.id }
        daos.workoutRoutePointDao.deleteForWorkouts(idsToDelete)
        if (ctx.hcIds.isEmpty()) {
            daos.workoutDao.deleteBetween(ctx.startMs, ctx.endMs)
        } else {
            daos.workoutDao.deleteWorkoutsNotIn(ctx.startMs, ctx.endMs, ctx.hcIds.toList())
        }
        return toAffectedRange(toDelete.minOf { it.startTime }, toDelete.maxOf { it.endTime }, ctx.zoneId)
    }

    private suspend fun reconcileHeartSource(
        daos: HealthRecordDaos,
        recordType: String,
        ctx: ReconcileContext,
    ): ScoreInvalidation.AffectedRange? {
        val localSources = daos.sourceRecordDao.getByRecordTypeAndRange(recordType, ctx.startMs, ctx.endMs)
        val toDelete = localSources.filter { it.sourceRecordId !in ctx.hcIds }
        if (toDelete.isEmpty()) return null

        toDelete.forEach {
            if (recordType == "HEART_RATE") {
                daos.heartRateDao.deleteBySourceRecordRef(it.id)
            } else if (recordType == "HRV") {
                daos.hrvDao.deleteBySourceRecordRef(it.id)
            }
            daos.sourceRecordDao.deleteBySourceRecordId(it.sourceRecordId)
        }
        return toAffectedRange(toDelete.minOf { it.createdAtMs }, toDelete.maxOf { it.createdAtMs }, ctx.zoneId)
    }

    private suspend fun <T> reconcileCompositeMetric(
        ctx: ReconcileContext,
        fetch: suspend (Long, Long) -> List<T>,
        getId: (T) -> String,
        getTimestamp: (T) -> Long,
        deleteById: suspend (String) -> Int,
        deleteBetween: suspend (Long, Long) -> Int,
    ): ScoreInvalidation.AffectedRange? {
        val local = fetch(ctx.startMs, ctx.endMs)
        val toDelete = local.filter { getId(it) !in ctx.hcIds && getId(it).substringBefore('_') !in ctx.hcIds }
        if (toDelete.isEmpty()) return null

        toDelete.forEach { deleteById(getId(it)) }
        if (ctx.hcIds.isEmpty()) {
            deleteBetween(ctx.startMs, ctx.endMs)
        }
        return toAffectedRange(toDelete.minOf { getTimestamp(it) }, toDelete.maxOf { getTimestamp(it) }, ctx.zoneId)
    }

    private suspend fun reconcileSteps(
        daos: HealthRecordDaos,
        ctx: ReconcileContext,
    ): ScoreInvalidation.AffectedRange? {
        val local = daos.stepRecordDao.getBetween(ctx.startMs, ctx.endMs)
        val toDelete = local.filter { it.id !in ctx.hcIds }
        if (toDelete.isEmpty()) return null

        if (ctx.hcIds.isEmpty()) {
            daos.stepRecordDao.deleteBetween(ctx.startMs, ctx.endMs)
        } else {
            daos.stepRecordDao.deleteNotIn(ctx.startMs, ctx.endMs, ctx.hcIds.toList())
        }
        return toAffectedRange(toDelete.minOf { it.startTime }, toDelete.maxOf { it.endTime }, ctx.zoneId)
    }

    private fun toAffectedRange(
        minMs: Long,
        maxMs: Long,
        zoneId: ZoneId,
    ): ScoreInvalidation.AffectedRange =
        ScoreInvalidation.AffectedRange(
            start = Instant.ofEpochMilli(minMs).atZone(zoneId).toLocalDate(),
            endInclusive = Instant.ofEpochMilli(maxMs).atZone(zoneId).toLocalDate(),
        )
}

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

