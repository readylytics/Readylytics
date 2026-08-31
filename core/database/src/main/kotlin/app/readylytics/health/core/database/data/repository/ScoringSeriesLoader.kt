package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.model.domain.model.TimestampedTrimp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringSeriesLoader
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val dailySummaryDao: DailySummaryDao,
    ) {
        suspend fun loadPreviousDaysSummaries(previousDaysMs: List<Long>): List<DailySummaryEntity> =
            dailySummaryDao.getByDates(previousDaysMs)

        suspend fun loadWorkoutTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp> =
            workoutDao.getTrimpPoints(fromMs, toMs)

        suspend fun loadEverydayTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp> =
            dailySummaryDao.getEverydayTrimpPoints(fromMs, toMs)
    }
