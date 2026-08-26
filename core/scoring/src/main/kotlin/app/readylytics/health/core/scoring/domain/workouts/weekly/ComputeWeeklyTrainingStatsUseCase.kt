package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.service.DateRange
import app.readylytics.health.core.model.domain.util.WeekBounds
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Pure aggregation of a user's weekly training activity, feeding every Workout-tab weekly
 * visualization (weekly totals, this-week-vs-last-week daily cumulative chart, activity volume,
 * training mix) from one shared pass over [workouts] so none of them can disagree with each other.
 *
 * Callers should fetch [workouts] with a single `WorkoutRepository.getInRange` query spanning
 * `WeekBounds.previousWeekFull(anchor, weekStartDay).start` through
 * `WeekBounds.currentWeekFull(anchor, weekStartDay).end` inclusive — that one range covers
 * everything every result field needs under either comparison mode.
 *
 * Week semantics depend on whether [anchor]'s configured week is still in progress:
 * - Anchor week == [realToday]'s week (in-progress): totals/comparison/activityVolumes compare
 *   like-for-like — week start through [anchor] against the same elapsed day-count one week back —
 *   so an unfinished week is never measured against a full previous week. Only the cumulative
 *   daily chart covers the FULL previous week (fixed-length x-axis), and current-week days after
 *   [anchor] are null rather than fabricated.
 * - Anchor in a fully past week: both sides are complete history, so the ENTIRE configured week
 *   containing [anchor] is compared against the ENTIRE previous configured week, and all seven
 *   cumulative-chart days are populated.
 */
class ComputeWeeklyTrainingStatsUseCase
    @Inject
    constructor() {
        fun execute(
            workouts: List<WorkoutData>,
            anchor: LocalDate,
            realToday: LocalDate,
            weekStartDay: DayOfWeek,
            zoneId: ZoneId,
        ): WeeklyTrainingStats {
            val isCurrentWeek =
                WeekBounds.weekStartOnOrBefore(anchor, weekStartDay) ==
                    WeekBounds.weekStartOnOrBefore(realToday, weekStartDay)
            val currentPeriod =
                if (isCurrentWeek) {
                    WeekBounds.currentWeekToDate(anchor, weekStartDay)
                } else {
                    WeekBounds.currentWeekFull(anchor, weekStartDay)
                }
            val previousPeriod =
                if (isCurrentWeek) {
                    WeekBounds.previousWeekToDate(anchor, weekStartDay)
                } else {
                    WeekBounds.previousWeekFull(anchor, weekStartDay)
                }
            val currentFull = WeekBounds.currentWeekFull(anchor, weekStartDay)
            val previousFull = WeekBounds.previousWeekFull(anchor, weekStartDay)

            val datedWorkouts = workouts.map { it to workoutDate(it, zoneId) }

            val currentToDateWorkouts = datedWorkouts.filter { (_, date) -> currentPeriod.contains(date) }
            val previousToDateWorkouts = datedWorkouts.filter { (_, date) -> previousPeriod.contains(date) }

            val currentTotals = totalsFor(currentToDateWorkouts)
            val previousTotals = totalsFor(previousToDateWorkouts)

            return WeeklyTrainingStats(
                currentPeriod = currentPeriod,
                previousPeriod = previousPeriod,
                currentWeek = currentTotals,
                previousWeek = previousTotals,
                comparison = comparisonFor(currentTotals, previousTotals),
                cumulativeDailyTraining =
                    buildDailyTraining(
                        datedWorkouts = datedWorkouts,
                        // Past weeks are complete: no day of their full window counts as "future".
                        today = if (isCurrentWeek) anchor else currentPeriod.end,
                        currentFull = currentFull,
                        previousFull = previousFull,
                    ),
                activityVolumes =
                    WeeklyActivityBreakdown.activityVolumes(currentToDateWorkouts, previousToDateWorkouts),
                trainingMix =
                    WeeklyActivityBreakdown.trainingMix(currentToDateWorkouts, currentTotals.totalDurationMinutes),
            )
        }

        private fun workoutDate(
            workout: WorkoutData,
            zoneId: ZoneId,
        ): LocalDate = Instant.ofEpochMilli(workout.startTime).atZone(zoneId).toLocalDate()

        private fun totalsFor(datedWorkouts: List<Pair<WorkoutData, LocalDate>>): PeriodTotals =
            PeriodTotals(
                totalDurationMinutes = datedWorkouts.sumOf { (workout, _) -> workout.durationMinutes },
                workoutCount = datedWorkouts.size,
                activeDays = datedWorkouts.mapTo(mutableSetOf()) { (_, date) -> date }.size,
            )

        private fun comparisonFor(
            current: PeriodTotals,
            previous: PeriodTotals,
        ): PeriodComparison =
            PeriodComparison(
                durationDeltaMinutes = current.totalDurationMinutes - previous.totalDurationMinutes,
                durationPercentChange =
                    WeeklyActivityBreakdown.percentChange(
                        current = current.totalDurationMinutes.toFloat(),
                        previous = previous.totalDurationMinutes.toFloat(),
                    ),
                workoutCountDelta = current.workoutCount - previous.workoutCount,
                activeDaysDelta = current.activeDays - previous.activeDays,
            )

        private fun buildDailyTraining(
            datedWorkouts: List<Pair<WorkoutData, LocalDate>>,
            today: LocalDate,
            currentFull: DateRange,
            previousFull: DateRange,
        ): List<DailyTrainingVolume> {
            val currentDurationByDate = durationsByDate(datedWorkouts, currentFull)
            val previousDurationByDate = durationsByDate(datedWorkouts, previousFull)

            var currentCumulative = 0
            var previousCumulative = 0
            return (0 until DAYS_IN_WEEK).map { offset ->
                val date = currentFull.start.plusDays(offset.toLong())
                val previousDate = previousFull.start.plusDays(offset.toLong())

                val previousDuration = previousDurationByDate[previousDate] ?: 0
                previousCumulative += previousDuration

                val isFuture = date.isAfter(today)
                val currentDuration = if (isFuture) null else (currentDurationByDate[date] ?: 0)
                if (currentDuration != null) currentCumulative += currentDuration

                DailyTrainingVolume(
                    dayOffset = offset,
                    date = date,
                    currentWeekDurationMinutes = currentDuration,
                    previousWeekDurationMinutes = previousDuration,
                    currentWeekCumulativeMinutes = if (isFuture) null else currentCumulative,
                    previousWeekCumulativeMinutes = previousCumulative,
                )
            }
        }

        private fun durationsByDate(
            datedWorkouts: List<Pair<WorkoutData, LocalDate>>,
            range: DateRange,
        ): Map<LocalDate, Int> =
            datedWorkouts
                .filter { (_, date) -> range.contains(date) }
                .groupBy({ it.second }) { it.first.durationMinutes }
                .mapValues { (_, durations) -> durations.sum() }

        private companion object {
            const val DAYS_IN_WEEK = 7
        }
    }
