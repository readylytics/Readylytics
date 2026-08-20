package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Pure aggregation of the AI recommendation prompt's "Typical Workout Pattern" section (Section G).
 *
 * Workout day boundaries resolve in the caller's configured scoring zone (matching every other
 * date-boundary computation in the codebase), not UTC. Calendar-day presence is used for rest-day
 * and streak calculations: any day in the window without at least one included workout counts as a
 * rest day.
 */
class ComputeWorkoutPatternSummaryUseCase
    @Inject
    constructor() {
    fun execute(
        workouts: List<WorkoutData>,
        today: LocalDate,
        zoneId: ZoneId,
        lookbackMonths: Int = ScoringConstants.AiRecommendation.LOOKBACK_MONTHS,
    ): WorkoutPatternSummary {
        val windowStart = today.minusMonths(lookbackMonths.toLong())
        val windowDays = ChronoUnit.DAYS.between(windowStart, today) + 1

        val datedWorkouts = workouts.map { it to workoutDate(it, zoneId) }
        val included = datedWorkouts.filter { (_, date) -> !date.isBefore(windowStart) && !date.isAfter(today) }
        val trainingDates = included.mapTo(mutableSetOf()) { (_, date) -> date }
        val trainingCount = trainingDates.size
        val restDayCount = windowDays.toInt() - trainingCount

        val breakdown =
            included.groupBy { (workout, _) -> workout.exerciseType }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<String, List<Pair<WorkoutData, LocalDate>>>> { it.second.size }
                        .thenBy { it.first.lowercase() },
                )
                .map { (exerciseType, datedTypeWorkouts) ->
                    val typeWorkouts = datedTypeWorkouts.map { it.first }
                    ExerciseTypePattern(
                        exerciseType = exerciseType,
                        frequencyPerWeek =
                            typeWorkouts.size.toFloat() /
                                (lookbackMonths * AVERAGE_DAYS_PER_MONTH) *
                                7f,
                        averageTrimp = typeWorkouts.map { it.trimp }.average().toFloat(),
                        averageDurationMinutes =
                            typeWorkouts.map { it.durationMinutes.toFloat() }.average().toFloat(),
                        preferredDaysOfWeek = preferredDays(typeWorkouts, zoneId),
                    )
                }

        val todayIsTraining = today in trainingDates
        val mostRecentRestDayGapDays =
            if (!todayIsTraining) {
                0
            } else {
                var daysBack = 1L
                while (daysBack < windowDays && today.minusDays(daysBack) in trainingDates) {
                    daysBack++
                }
                daysBack.toInt()
            }
        val currentConsecutiveTrainingDayStreak =
            if (!todayIsTraining) {
                0
            } else {
                var daysBack = 0L
                while (daysBack < windowDays && today.minusDays(daysBack) in trainingDates) {
                    daysBack++
                }
                daysBack.toInt()
            }

        return WorkoutPatternSummary(
            lookbackMonths = lookbackMonths,
            totalWorkoutsInWindow = included.size,
            exerciseTypeBreakdown = breakdown,
            restDaysPerWeekAverage = restDayCount.toFloat() / windowDays.toFloat() * 7f,
            mostRecentRestDayGapDays = mostRecentRestDayGapDays,
            currentConsecutiveTrainingDayStreak = currentConsecutiveTrainingDayStreak,
        )
    }

    private fun workoutDate(workout: WorkoutData, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(workout.startTime).atZone(zoneId).toLocalDate()

    private fun preferredDays(workouts: List<WorkoutData>, zoneId: ZoneId): List<String> =
        workouts.groupingBy { workout ->
            Instant.ofEpochMilli(workout.startTime).atZone(zoneId).dayOfWeek
        }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<DayOfWeek, Int>> { it.value }
                    .thenBy { it.key.value },
            )
            .map { it.key.prettyName() }

    private fun DayOfWeek.prettyName(): String = getDisplayName(TextStyle.FULL, Locale.US)

    private companion object {
        const val AVERAGE_DAYS_PER_MONTH = 30.4375f
    }
}
