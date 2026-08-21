package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
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
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.util.logD
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

