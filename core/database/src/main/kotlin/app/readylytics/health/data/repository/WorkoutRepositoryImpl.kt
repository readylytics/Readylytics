package app.readylytics.health.data.repository

import androidx.room.withTransaction
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.domain.repository.RoutePoint
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.repository.WorkoutStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl
    @Inject
    constructor(
        private val dao: WorkoutDao,
        private val workoutRoutePointDao: WorkoutRoutePointDao,
        private val db: HealthDatabase,
    ) : WorkoutRepository {
        override suspend fun getById(id: String): WorkoutData? = dao.getById(id)?.let { mapToDomain(it) }

        override suspend fun getEarliestWorkoutTimestamp(): Long? = dao.getEarliestWorkoutTimestamp()

        override fun observeSince(fromMs: Long): Flow<List<WorkoutData>> =
            dao.observeSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        override suspend fun getRoutePoints(workoutId: String): List<RoutePoint> =
            workoutRoutePointDao.getRoutePoints(workoutId).map {
                RoutePoint(it.latitude, it.longitude, it.altitude, it.timestampMs)
            }

        override suspend fun updateRouteState(workoutId: String, routeState: String) {
            val workout = dao.getById(workoutId) ?: return
            dao.upsertAll(listOf(workout.copy(routeState = routeState)))
        }

        override suspend fun saveRoutePoints(workoutId: String, points: List<RoutePoint>, stats: WorkoutStats) {
            val workout = dao.getById(workoutId) ?: return

            db.withTransaction {
                val dbPoints = points.map {
                    WorkoutRoutePointEntity(
                        workoutId = workoutId,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        altitude = it.altitude,
                        timestampMs = it.timestampMs,
                        horizontalAccuracy = null,
                        verticalAccuracy = null
                    )
                }
                workoutRoutePointDao.deleteByWorkoutId(workoutId)
                workoutRoutePointDao.insertAll(dbPoints)

                dao.upsertAll(listOf(
                    workout.copy(
                        routeState = "IMPORTED",
                        avgSpeedKmh = stats.avgSpeedKmh,
                        avgPaceMinKm = stats.avgPaceMinKm,
                        elevationGainMeters = stats.elevationGainMeters,
                        totalDistanceMeters = stats.totalDistanceMeters
                    )
                ))
            }
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
                routeState = entity.routeState,
                avgSpeedKmh = entity.avgSpeedKmh,
                avgPaceMinKm = entity.avgPaceMinKm,
                elevationGainMeters = entity.elevationGainMeters,
                totalDistanceMeters = entity.totalDistanceMeters,
            )
    }

