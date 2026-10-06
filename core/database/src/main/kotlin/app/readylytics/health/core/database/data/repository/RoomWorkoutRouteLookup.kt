package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.model.domain.repository.StoredWorkoutRouteSnapshot
import app.readylytics.health.core.model.domain.repository.WorkoutRouteLookup
import javax.inject.Inject

class RoomWorkoutRouteLookup @Inject constructor(private val workoutDao: WorkoutDao) : WorkoutRouteLookup {
    override suspend fun snapshots(ids: List<String>): Map<String, StoredWorkoutRouteSnapshot> =
        ids.distinct().chunked(BIND_LIMIT).flatMap { chunk ->
            workoutDao.getByIds(chunk).map { workout ->
                StoredWorkoutRouteSnapshot(
                    workout.id,
                    workout.startTime,
                    workout.endTime,
                    workout.exerciseType,
                    workout.deviceName,
                    workout.routeState,
                )
            }
        }.associateBy { it.id }

    private companion object {
        const val BIND_LIMIT = 900
    }
}
