package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.scoring.ScoringConstants
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure aggregation of the AI recommendation prompt's "Typical Workout Pattern" section (Section G).
 *
 * Workout day boundaries are resolved in UTC, documented as the fixture and runtime convention
 * for this personalization summary. Calendar-day presence is used for rest-day and streak
 * calculations: any day in the window without at least one included workout counts as a rest day.
 */
class ComputeWorkoutPatternSummaryUseCase {
    fun execute(
        workouts: List<WorkoutData>,
        today: LocalDate,
        lookbackMonths: Int = ScoringConstants.AiRecommendation.LOOKBACK_MONTHS,
    ): WorkoutPatternSummary {
        val windowStart = today.minusMonths(lookbackMonths.toLong())
        val windowDays = ChronoUnit.DAYS.between(windowStart, today) + 1
        val included =
            workouts.filter { workout ->
                val date = workoutDate(workout)
                !date.isBefore(windowStart) && !date.isAfter(today)
            }
        val trainingDates =
            buildSet {
                included.forEach { workout -> add(workoutDate(workout)) }
            }
        val trainingCount = trainingDates.size
        val restDayCount = windowDays.toInt() - trainingCount

        val breakdown =
            included.groupBy { it.exerciseType }
                .toList()
                .sortedWith(
                    compareByDescending<Pair<String, List<WorkoutData>>> { it.second.size }
                        .thenBy { it.first.lowercase() },
                )
                .map { (exerciseType, typeWorkouts) ->
                    ExerciseTypePattern(
                        exerciseType = exerciseType,
                        frequencyPerWeek =
                            typeWorkouts.size.toFloat() /
                                (lookbackMonths * AVERAGE_DAYS_PER_MONTH) *
                                7f,
                        averageTrimp = typeWorkouts.map { it.trimp }.average().toFloat(),
                        averageDurationMinutes =
                            typeWorkouts.map { it.durationMinutes.toFloat() }.average().toFloat(),
                        averageLoadClassification = null,
                        preferredDaysOfWeek = preferredDays(typeWorkouts),
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

    private fun workoutDate(workout: WorkoutData): LocalDate =
        Instant.ofEpochMilli(workout.startTime).atZone(ZoneOffset.UTC).toLocalDate()

    private fun preferredDays(workouts: List<WorkoutData>): List<String> =
        workouts.groupingBy { workout ->
            Instant.ofEpochMilli(workout.startTime).atZone(ZoneOffset.UTC).dayOfWeek
        }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<DayOfWeek, Int>> { it.value }
                    .thenBy { it.key.value },
            )
            .map { it.key.prettyName() }

    private fun DayOfWeek.prettyName(): String =
        name.lowercase().replaceFirstChar { char -> char.uppercase() }

    private companion object {
        const val AVERAGE_DAYS_PER_MONTH = 30.4375f
    }
}
