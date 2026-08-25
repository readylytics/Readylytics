package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutTypeMapper
import java.time.LocalDate

/** Per-activity-type volume and training-mix aggregation shared by the weekly Workout visualizations. */
internal object WeeklyActivityBreakdown {
    fun activityVolumes(
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

    fun trainingMix(
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

    fun percentChange(
        current: Float,
        previous: Float,
    ): Float? = if (previous == 0f) null else (current - previous) / previous * PERCENT_SCALE

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

    private const val PERCENT_SCALE = 100f
}
