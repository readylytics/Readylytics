package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.repository.map
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.SessionSpans
import app.readylytics.health.core.model.domain.sync.mergeEnrichment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHealthChangeIngestionStore
    @Inject
    constructor(
        private val daos: HealthRecordDaos,
        private val dirtyRangeStore: RoomDirtyRangeStore? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
        private val settingsRepo: SettingsRepository? = null,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val transactionRunner: TransactionRunner? = null,
        private val vo2MaxRecordDao: Vo2MaxRecordDao? = null,
    ) : HealthChangeIngestionStore {
        override suspend fun affectedDatesForRecord(
            type: HealthDataType,
            hcRecordId: String,
            zoneId: ZoneId,
        ): Set<LocalDate> =
            when (type) {
                HealthDataType.SLEEP ->
                    daos.sleepSessionDao.getById(hcRecordId)?.let {
                        datesBetween(it.startTime, it.endTime, zoneId)
                    } ?: emptySet()
                HealthDataType.HEART_RATE ->
                    daos.sourceRecordDao.getSourceRef(hcRecordId)?.let { ref ->
                        daos.heartRateDao.getBySourceRecordRef(ref)
                            .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                    } ?: emptySet()
                HealthDataType.HRV ->
                    daos.sourceRecordDao.getSourceRef(hcRecordId)?.let { ref ->
                        daos.hrvDao.getBySourceRecordRef(ref)
                            .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                    } ?: emptySet()
                HealthDataType.EXERCISE ->
                    daos.workoutDao.getById(hcRecordId)?.let {
                        datesBetween(it.startTime, it.endTime, zoneId)
                    } ?: emptySet()
                HealthDataType.WEIGHT ->
                    daos.weightRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.BODY_FAT ->
                    daos.bodyFatRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.BLOOD_PRESSURE ->
                    daos.bloodPressureRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.OXYGEN_SATURATION ->
                    daos.oxygenSaturationRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.BODY_TEMPERATURE ->
                    daos.bodyTemperatureRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.STEPS ->
                    daos.stepRecordDao.getById(hcRecordId)?.let {
                        datesBetween(it.startTime, it.endTime, zoneId)
                    } ?: emptySet()
                HealthDataType.VO2_MAX ->
                    // VO2 max keeps its raw stable HC id (no timestamp suffix, unlike the
                    // composite-keyed vitals above), so a direct primary-key lookup resolves
                    // the pre-delete timestamp for the P2 dirty-range journal.
                    vo2MaxRecordDao?.getById(hcRecordId)?.let { setOf(dateFor(it.timestampMs, zoneId)) }
                        ?: emptySet()
            }

        override suspend fun deleteRecord(type: HealthDataType, hcRecordId: String) {
            val zoneId =
                try {
                    settingsRepo?.userPreferences?.first()?.scoringZone() ?: clock.zone
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    clock.zone
                }
            deleteRecordAndJournal(type, hcRecordId, zoneId)
        }

        suspend fun deleteRecordAndJournal(
            type: HealthDataType,
            hcRecordId: String,
            zoneId: ZoneId,
            reason: String = "RECORD_DELETION",
            snapshotId: String = "ACTIVE",
            today: LocalDate = LocalDate.now(clock.withZone(zoneId)),
        ): Set<LocalDate> =
            inTransaction {
                val affected = affectedDatesForRecord(type, hcRecordId, zoneId)
                if (affected.isNotEmpty() && dirtyRangeStore != null && healthMutationStateDao != null) {
                    val earliest = affected.minOrNull()!!
                    val end = maxOf(today, affected.maxOrNull()!!)
                    healthMutationStateDao.incrementGeneration()
                    dirtyRangeStore.append(
                        start = earliest,
                        endInclusive = end,
                        reason = reason,
                        snapshotId = snapshotId,
                    )
                }
                deleteFromDaos(type, hcRecordId)
                affected
            }

        private suspend fun <R> inTransaction(block: suspend () -> R): R =
            transactionRunner?.runInTransaction(block) ?: block()

        private suspend fun deleteFromDaos(type: HealthDataType, hcRecordId: String) {
            when (type) {
                HealthDataType.SLEEP -> daos.sleepSessionDao.deleteById(hcRecordId)
                HealthDataType.HEART_RATE -> {
                    daos.sourceRecordDao.getSourceRef(hcRecordId)
                        ?.let { daos.heartRateDao.deleteBySourceRecordRef(it) }
                    daos.sourceRecordDao.deleteBySourceRecordId(hcRecordId)
                }
                HealthDataType.HRV -> {
                    daos.sourceRecordDao.getSourceRef(hcRecordId)
                        ?.let { daos.hrvDao.deleteBySourceRecordRef(it) }
                    daos.sourceRecordDao.deleteBySourceRecordId(hcRecordId)
                }
                HealthDataType.EXERCISE -> daos.workoutDao.deleteById(hcRecordId)
                HealthDataType.WEIGHT -> daos.weightRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.BODY_FAT -> daos.bodyFatRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.BLOOD_PRESSURE -> daos.bloodPressureRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.OXYGEN_SATURATION ->
                    daos.oxygenSaturationRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.BODY_TEMPERATURE ->
                    daos.bodyTemperatureRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.STEPS -> daos.stepRecordDao.deleteById(hcRecordId)
                HealthDataType.VO2_MAX -> vo2MaxRecordDao?.deleteById(hcRecordId)
            }
        }

        override suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans =
            SessionSpans(
                sleepSessions = daos.sleepSessionDao.getOverlapping(startMs, endMs).map { it.toInput() },
                workouts = daos.workoutDao.getOverlapping(startMs, endMs).map { it.toInput() },
            )

        override suspend fun heartRateSamplesForMetrics(
            recordType: String,
            startMs: Long,
            endMs: Long,
        ): List<DomainHeartRateSample> =
            daos.heartRateDao.getByTypeAndTimeRange(recordType, startMs, endMs).map {
                DomainHeartRateSample(time = Instant.ofEpochMilli(it.timestampMs), beatsPerMinute = it.beatsPerMinute)
            }

        private fun datesBetween(startMs: Long, endMs: Long, zoneId: ZoneId): Set<LocalDate> {
            val startDate = Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMs).atZone(zoneId).toLocalDate()
            val dates = mutableSetOf<LocalDate>()
            var current = startDate
            while (!current.isAfter(endDate)) {
                dates.add(current)
                current = current.plusDays(1)
            }
            return dates
        }

        private fun dateFor(timestampMs: Long, zoneId: ZoneId): LocalDate =
            Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate()

        override suspend fun persistPreparedWorkouts(prepared: List<PreparedWorkout>) {
            if (prepared.isEmpty()) return
            inTransaction {
                val entities = prepared.map { it.toMergedEntity(daos.workoutDao.getById(it.workout.id)) }
                daos.workoutDao.upsertAll(entities)
                prepared.forEach { applyRoutePoints(daos, it) }
            }
        }
    }

/**
 * Resolves [PreparedWorkout.route]/[PreparedWorkout.distanceMeters]/[PreparedWorkout.elevationMeters]
 * against [existing] via [mergeEnrichment]: an Available outcome (including an authoritatively
 * empty/null value) replaces, Denied/Unsupported preserves the stored value. `avgSpeedKmh` is
 * always re-derived from the *selected* distance and this workout's own duration, and cleared
 * when that distance is authoritatively absent -- never preserved independently of distance.
 */
private fun PreparedWorkout.toMergedEntity(existing: WorkoutRecordEntity?): WorkoutRecordEntity {
    val distanceMeters = mergeEnrichment(existing?.totalDistanceMeters, this.distanceMeters)
    val elevationGainMeters = mergeEnrichment(existing?.elevationGainMeters, elevationMeters)
    val routeState =
        mergeEnrichment(
            existing?.routeState ?: RouteState.NOT_AVAILABLE,
            route.map { points -> if (points.isNotEmpty()) RouteState.IMPORTED else RouteState.NOT_AVAILABLE },
        )
    val avgSpeedKmh = deriveWorkoutAvgSpeedKmh(distanceMeters, workout.startTime, workout.endTime)
    return workout.toEntity().copy(
        modelTrimp = existing?.modelTrimp,
        totalDistanceMeters = distanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
    )
}

/**
 * Touches `workout_route_points` only when [PreparedWorkout.route] is [ReadOutcome.Available] --
 * a Denied/Unsupported read must never delete an already-imported route it merely couldn't
 * re-read this round (H5/WP-09).
 */
private suspend fun applyRoutePoints(daos: HealthRecordDaos, prepared: PreparedWorkout) {
    val route = prepared.route
    if (route !is ReadOutcome.Available) return
    daos.workoutRoutePointDao.deleteForWorkouts(listOf(prepared.workout.id))
    if (route.data.isNotEmpty()) {
        daos.workoutRoutePointDao.insertAll(route.data.map(WorkoutRoutePoint::toEntity))
    }
}
