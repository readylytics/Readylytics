package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.model.domain.model.TimestampedTrimp
import app.readylytics.health.core.model.domain.util.toLocalDateIn
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

        /**
         * True first-data day for the minimum-history guard: the earlier of the first ingested
         * daily summary and the first workout, unbounded by any lookback window. `null` means no
         * data exists yet.
         *
         * The workout lookup is skipped once the summary date alone already satisfies
         * [satisfiedBefore] (the guard cannot trigger for any earlier start), saving a query on
         * the common established-user path. Pass `null` to always resolve the exact earliest day.
         */
        suspend fun loadTrackingStartDate(
            zoneId: ZoneId,
            satisfiedBefore: LocalDate? = null,
        ): LocalDate? {
            val summaryStart = dailySummaryDao.getEarliestDateMs()?.toLocalDateIn(zoneId)
            if (summaryStart != null && satisfiedBefore != null && !summaryStart.isAfter(satisfiedBefore)) {
                return summaryStart
            }
            val workoutStart = workoutDao.getEarliestWorkoutTimestamp()?.toLocalDateIn(zoneId)
            return listOfNotNull(summaryStart, workoutStart).minOrNull()
        }
    }
