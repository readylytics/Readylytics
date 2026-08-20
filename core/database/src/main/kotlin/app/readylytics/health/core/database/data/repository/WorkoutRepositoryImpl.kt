package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl
    @Inject
    constructor(
        private val dao: WorkoutDao,
        private val routePointDao: WorkoutRoutePointDao,
    ) : WorkoutRepository {
        override suspend fun getById(id: String): WorkoutData? = dao.getById(id)?.let { mapToDomain(it) }

        override suspend fun getEarliestWorkoutTimestamp(): Long? = dao.getEarliestWorkoutTimestamp()

        override suspend fun getInRange(fromMs: Long, toMs: Long): List<WorkoutData> =
            dao.getWorkoutsInRange(fromMs, toMs).map { mapToDomain(it) }

        override suspend fun getInRangePaged(
            fromMs: Long,
            toMs: Long,
            limit: Int,
            offset: Int,
        ): List<WorkoutData> =
            dao.getPagedInRange(fromMs, toMs, limit, offset).map { mapToDomain(it) }

        override suspend fun countByTimeRange(fromMs: Long, toMs: Long): Int =
            dao.countByTimeRange(fromMs, toMs)

        override suspend fun getRoutePoints(workoutId: String): List<WorkoutRoutePoint> =
            routePointDao.getRoutePoints(workoutId).map { it.toDomain() }

        override fun observeSince(fromMs: Long): Flow<List<WorkoutData>> =
            dao.observeSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        private fun mapToDomain(entity: WorkoutRecordEntity): WorkoutData =
            WorkoutData(
                id = entity.id,
                startTime = entity.startTime,
                endTime = entity.endTime,
                exerciseType = entity.exerciseType,
                durationMinutes = entity.durationMinutes,
                zone1Minutes = entity.zone1Minutes,
                zone2Minutes = entity.zone2Minutes,
                zone3Minutes = entity.zone3Minutes,
                zone4Minutes = entity.zone4Minutes,
                zone5Minutes = entity.zone5Minutes,
                trimp = entity.trimp,
                avgHr = entity.avgHr,
                deviceName = entity.deviceName,
                totalDistanceMeters = entity.totalDistanceMeters,
                avgSpeedKmh = entity.avgSpeedKmh,
                elevationGainMeters = entity.elevationGainMeters,
                routeState = entity.routeState,
            )

        private fun WorkoutRoutePointEntity.toDomain() =
            WorkoutRoutePoint(
                id = id,
                workoutId = workoutId,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                timestampMs = timestampMs,
                horizontalAccuracy = horizontalAccuracy,
                verticalAccuracy = verticalAccuracy,
            )
    }
