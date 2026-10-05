package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.repository.map
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.mergeEnrichment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun datesBetween(startMs: Long, endMs: Long, zoneId: ZoneId): Set<LocalDate> {
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

fun dateFor(timestampMs: Long, zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate()

suspend fun <R> TransactionRunner?.runOrDirect(block: suspend () -> R): R =
    this?.runInTransaction(block) ?: block()

suspend fun sessionDatesFor(
    daos: HealthRecordDaos,
    sessionId: String?,
    zoneId: ZoneId,
): List<LocalDate> {
    val sid = sessionId ?: return emptyList()
    val sleepDate = daos.sleepSessionDao.getById(sid)?.let { dateFor(it.endTime, zoneId) }
    val workoutDate = daos.workoutDao.getById(sid)?.let { dateFor(it.startTime, zoneId) }
    return listOfNotNull(sleepDate, workoutDate)
}

suspend fun HealthRecordDaos.datesForSourceRefs(
    sourceRefs: List<Long>,
    fetchRecords: suspend (List<Long>) -> List<Pair<Long, String?>>,
    zoneId: ZoneId,
): Set<LocalDate> {
    if (sourceRefs.isEmpty()) return emptySet()
    val dates = mutableSetOf<LocalDate>()
    fetchRecords(sourceRefs).forEach { (timestampMs, sessionId) ->
        dates.add(dateFor(timestampMs, zoneId))
        dates.addAll(sessionDatesFor(this, sessionId, zoneId))
    }
    return dates
}

suspend fun HealthRecordDaos.datesForSourceRef(
    sourceRef: Long?,
    fetchRecords: suspend (Long) -> List<Pair<Long, String?>>,
    zoneId: ZoneId,
): Set<LocalDate> {
    val ref = sourceRef ?: return emptySet()
    val dates = mutableSetOf<LocalDate>()
    fetchRecords(ref).forEach { (timestampMs, sessionId) ->
        dates.add(dateFor(timestampMs, zoneId))
        dates.addAll(sessionDatesFor(this, sessionId, zoneId))
    }
    return dates
}

suspend fun deleteFromDaos(
    daos: HealthRecordDaos,
    vo2MaxRecordDao: Vo2MaxRecordDao?,
    type: HealthDataType,
    hcRecordId: String,
) {
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

suspend fun deleteFromDaosPlural(
    daos: HealthRecordDaos,
    vo2MaxRecordDao: Vo2MaxRecordDao?,
    type: HealthDataType,
    ids: List<String>,
) {
    when (type) {
        HealthDataType.SLEEP -> daos.sleepSessionDao.deleteByIds(ids)
        HealthDataType.HEART_RATE -> {
            val refs = daos.sourceRecordDao.getSourceRefs(ids)
            if (refs.isNotEmpty()) daos.heartRateDao.deleteBySourceRecordRefs(refs)
            daos.sourceRecordDao.deleteBySourceRecordIds(ids)
        }
        HealthDataType.HRV -> {
            val refs = daos.sourceRecordDao.getSourceRefs(ids)
            if (refs.isNotEmpty()) daos.hrvDao.deleteBySourceRecordRefs(refs)
            daos.sourceRecordDao.deleteBySourceRecordIds(ids)
        }
        HealthDataType.EXERCISE -> daos.workoutDao.deleteByIds(ids)
        HealthDataType.WEIGHT -> daos.weightRecordDao.deleteBySourceRecordIds(ids)
        HealthDataType.BODY_FAT -> daos.bodyFatRecordDao.deleteBySourceRecordIds(ids)
        HealthDataType.BLOOD_PRESSURE -> daos.bloodPressureRecordDao.deleteBySourceRecordIds(ids)
        HealthDataType.OXYGEN_SATURATION ->
            daos.oxygenSaturationRecordDao.deleteBySourceRecordIds(ids)
        HealthDataType.BODY_TEMPERATURE ->
            daos.bodyTemperatureRecordDao.deleteBySourceRecordIds(ids)
        HealthDataType.STEPS -> daos.stepRecordDao.deleteByIds(ids)
        HealthDataType.VO2_MAX -> vo2MaxRecordDao?.deleteByIds(ids)
    }
}

fun PreparedWorkout.toMergedEntity(existing: WorkoutRecordEntity?): WorkoutRecordEntity {
    val distanceMeters = mergeEnrichment(existing?.totalDistanceMeters, this.distanceMeters)
    val elevationGainMeters = mergeEnrichment(existing?.elevationGainMeters, elevationMeters)
    val routeState =
        mergeEnrichment(
            existing?.routeState ?: RouteState.NOT_AVAILABLE,
            route.map { points -> if (points.isNotEmpty()) RouteState.IMPORTED else RouteState.NOT_AVAILABLE },
        )
    val avgSpeedKmh = app.readylytics.health.core.database.data.local.deriveWorkoutAvgSpeedKmh(distanceMeters, workout.startTime, workout.endTime)
    return workout.toEntity().copy(
        modelTrimp = existing?.modelTrimp,
        modelTrimpSourceRevision = existing?.modelTrimpSourceRevision,
        modelTrimpSnapshotId = existing?.modelTrimpSnapshotId,
        modelTrimpAlgorithmRevision = existing?.modelTrimpAlgorithmRevision,
        modelTrimpQuality = existing?.modelTrimpQuality,
        totalDistanceMeters = distanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
    )
}

suspend fun applyRoutePoints(daos: HealthRecordDaos, prepared: PreparedWorkout) {
    val route = prepared.route
    if (route !is ReadOutcome.Available) return
    daos.workoutRoutePointDao.deleteForWorkouts(listOf(prepared.workout.id))
    if (route.data.isNotEmpty()) {
        daos.workoutRoutePointDao.insertAll(route.data.map(WorkoutRoutePoint::toEntity))
    }
}

suspend fun datesForChunk(
    daos: HealthRecordDaos,
    vo2MaxRecordDao: Vo2MaxRecordDao?,
    type: HealthDataType,
    chunk: List<String>,
    zoneId: ZoneId,
): Collection<LocalDate> = when (type) {
    HealthDataType.SLEEP ->
        daos.sleepSessionDao.getByIds(chunk).flatMap {
            datesBetween(it.startTime, it.endTime, zoneId)
        }
    HealthDataType.HEART_RATE ->
        daos.datesForSourceRefs(
            daos.sourceRecordDao.getSourceRefs(chunk),
            { daos.heartRateDao.getBySourceRecordRefs(it).map { r -> r.timestampMs to r.sessionId } },
            zoneId,
        )
    HealthDataType.HRV ->
        daos.datesForSourceRefs(
            daos.sourceRecordDao.getSourceRefs(chunk),
            { daos.hrvDao.getBySourceRecordRefs(it).map { r -> r.timestampMs to r.sessionId } },
            zoneId,
        )
    HealthDataType.EXERCISE ->
        daos.workoutDao.getByIds(chunk).flatMap {
            datesBetween(it.startTime, it.endTime, zoneId)
        }
    HealthDataType.WEIGHT ->
        daos.weightRecordDao.getBySourceRecordIds(chunk).map { dateFor(it.timestampMs, zoneId) }
    HealthDataType.BODY_FAT ->
        daos.bodyFatRecordDao.getBySourceRecordIds(chunk).map { dateFor(it.timestampMs, zoneId) }
    HealthDataType.BLOOD_PRESSURE ->
        daos.bloodPressureRecordDao.getBySourceRecordIds(chunk)
            .map { dateFor(it.timestampMs, zoneId) }
    HealthDataType.OXYGEN_SATURATION ->
        daos.oxygenSaturationRecordDao.getBySourceRecordIds(chunk)
            .map { dateFor(it.timestampMs, zoneId) }
    HealthDataType.BODY_TEMPERATURE ->
        daos.bodyTemperatureRecordDao.getBySourceRecordIds(chunk)
            .map { dateFor(it.timestampMs, zoneId) }
    HealthDataType.STEPS ->
        daos.stepRecordDao.getByIds(chunk).flatMap {
            datesBetween(it.startTime, it.endTime, zoneId)
        }
    HealthDataType.VO2_MAX ->
        vo2MaxRecordDao?.getByIds(chunk)?.map { dateFor(it.timestampMs, zoneId) } ?: emptyList()
}
