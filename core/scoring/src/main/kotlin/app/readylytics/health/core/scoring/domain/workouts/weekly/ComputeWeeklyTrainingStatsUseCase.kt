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
 * visualization (weekly totals, this-week-vs-last-week daily chart, activity volume, training
 * mix) from one shared pass over [workouts] so none of them can disagree with each other.
 *
 * Callers should fetch [workouts] with a single `WorkoutRepository.getInRange` query spanning
 * `WeekBounds.previousWeekFull(today, weekStartDay).start` through `today` (inclusive) — that one
 * range covers everything every result field needs.
 *
 * Week semantics: every previous-week figure ([WeeklyTrainingStats.previousWeek], `comparison`,
 * `activityVolumes`, and the chart's previous-week line) covers the FULL previous configured week,
 * so a "-10% vs last week" label agrees with the chart. Only the current side is truncated to
 * `today` — future current-week days have no data yet.
 */
class ComputeWeeklyTrainingStatsUseCase
    @Inject
    constructor() {
        fun execute(
            workouts: List<WorkoutData>,
            today: LocalDate,
            weekStartDay: DayOfWeek,
            zoneId: ZoneId,
        ): WeeklyTrainingStats {
            val currentToDate = WeekBounds.currentWeekToDate(today, weekStartDay)
            val currentFull = WeekBounds.currentWeekFull(today, weekStartDay)
            val previousFull = WeekBounds.previousWeekFull(today, weekStartDay)

            val datedWorkouts = workouts.map { it to workoutDate(it, zoneId) }

            val currentToDateWorkouts = datedWorkouts.filter { (_, date) -> currentToDate.contains(date) }
            val previousFullWorkouts = datedWorkouts.filter { (_, date) -> previousFull.contains(date) }

            val currentTotals = totalsFor(currentToDateWorkouts)
            val previousTotals = totalsFor(previousFullWorkouts)

            return WeeklyTrainingStats(
                currentPeriod = currentToDate,
                previousPeriod = previousFull,
                currentWeek = currentTotals,
                previousWeek = previousTotals,
                comparison = comparisonFor(currentTotals, previousTotals),
                cumulativeDailyTraining = buildDailyTraining(datedWorkouts, today, currentFull, previousFull),
                activityVolumes =
                    WeeklyActivityBreakdown.activityVolumes(currentToDateWorkouts, previousFullWorkouts),
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
