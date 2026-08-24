package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.service.DateRange
import app.readylytics.health.core.model.domain.util.WeekBounds
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutTypeMapper
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
            val previousToDate = WeekBounds.previousWeekToDate(today, weekStartDay)
            val currentFull = WeekBounds.currentWeekFull(today, weekStartDay)
            val previousFull = WeekBounds.previousWeekFull(today, weekStartDay)

            val datedWorkouts = workouts.map { it to workoutDate(it, zoneId) }

            val currentToDateWorkouts = datedWorkouts.filter { (_, date) -> currentToDate.contains(date) }
            val previousToDateWorkouts = datedWorkouts.filter { (_, date) -> previousToDate.contains(date) }

            val currentTotals = totalsFor(currentToDateWorkouts)
            val previousTotals = totalsFor(previousToDateWorkouts)

            return WeeklyTrainingStats(
                currentPeriod = currentToDate,
                previousPeriod = previousToDate,
                currentWeek = currentTotals,
                previousWeek = previousTotals,
                comparison = comparisonFor(currentTotals, previousTotals),
                cumulativeDailyTraining = buildDailyTraining(datedWorkouts, today, currentFull, previousFull),
                activityVolumes = buildActivityVolumes(currentToDateWorkouts, previousToDateWorkouts),
                trainingMix = buildTrainingMix(currentToDateWorkouts, currentTotals.totalDurationMinutes),
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
                    percentChange(
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

        private fun buildActivityVolumes(
            currentToDateWorkouts: List<Pair<WorkoutData, LocalDate>>,
            previousToDateWorkouts: List<Pair<WorkoutData, LocalDate>>,
        ): List<ActivityVolume> {
            val currentByType = currentToDateWorkouts.map { it.first }.groupBy(::classify)
            val previousByType = previousToDateWorkouts.map { it.first }.groupBy(::classify)
            val types = currentByType.keys + previousByType.keys

            return types.sortedBy { it.ordinal }.map { type ->
                val metricType = ActivityMetricTypeMapper.metricTypeFor(type)
                val currentValue = metricValue(currentByType[type].orEmpty(), metricType)
                val previousValue = metricValue(previousByType[type].orEmpty(), metricType)
                ActivityVolume(
                    activityType = type,
                    metricType = metricType,
                    currentWeekValue = currentValue,
                    previousWeekValue = previousValue,
                    absoluteChange = currentValue - previousValue,
                    percentChange = percentChange(currentValue, previousValue),
                )
            }
        }

        private fun buildTrainingMix(
            currentToDateWorkouts: List<Pair<WorkoutData, LocalDate>>,
            totalDurationMinutes: Int,
        ): List<TrainingMixItem> {
            if (totalDurationMinutes <= 0) return emptyList()
            return currentToDateWorkouts
                .map { it.first }
                .groupBy(::classify)
                .map { (type, workoutsOfType) ->
                    val duration = workoutsOfType.sumOf { it.durationMinutes }
                    TrainingMixItem(
                        activityType = type,
                        durationMinutes = duration,
                        percentage = duration.toFloat() / totalDurationMinutes * PERCENT_SCALE,
                    )
                }.sortedBy { it.activityType.ordinal }
        }

        private fun classify(workout: WorkoutData): WorkoutLayoutType =
            WorkoutLayoutTypeMapper.fromExerciseType(workout.exerciseType)

        private fun metricValue(
            workouts: List<WorkoutData>,
            metricType: ActivityMetricType,
        ): Float =
            when (metricType) {
                ActivityMetricType.DISTANCE -> workouts.sumOf { (it.totalDistanceMeters ?: 0f).toDouble() }.toFloat()
                ActivityMetricType.DURATION -> workouts.sumOf { it.durationMinutes }.toFloat()
            }

        private fun percentChange(
            current: Float,
            previous: Float,
        ): Float? = if (previous == 0f) null else (current - previous) / previous * PERCENT_SCALE

        private companion object {
            const val DAYS_IN_WEEK = 7
            const val PERCENT_SCALE = 100f
        }
    }
