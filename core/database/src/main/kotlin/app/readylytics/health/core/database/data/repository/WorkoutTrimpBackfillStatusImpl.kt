package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.model.domain.repository.WorkoutTrimpBackfillStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutTrimpBackfillStatusImpl
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
    ) : WorkoutTrimpBackfillStatus {
        override suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean =
            workoutDao.countUnbackfilledSince(retentionStartMs) > 0
    }
