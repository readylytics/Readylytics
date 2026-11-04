package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.model.domain.model.TimestampedTrimp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

        /** True first workout (unbounded by any lookback window), for the minimum-history guard. */
        suspend fun loadEarliestWorkoutDate(zoneId: ZoneId): LocalDate? =
            workoutDao.getEarliestWorkoutTimestamp()?.toLocalDate(zoneId)

        /** True first ingested day (unbounded by any lookback window), for the minimum-history guard. */
        suspend fun loadEarliestSummaryDate(zoneId: ZoneId): LocalDate? =
            dailySummaryDao.getEarliestDateMs()?.toLocalDate(zoneId)

        private fun Long.toLocalDate(zoneId: ZoneId): LocalDate =
            Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
    }
