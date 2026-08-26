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

        val items =
            currentToDateWorkouts
                .map { it.first }
                .groupBy(::classify)
                .mapNotNull { (type, workoutsOfType) ->
                    val duration = workoutsOfType.sumOf { it.durationMinutes }
                    if (duration <= 0) {
                        null
                    } else {
                        TrainingMixItem(
                            activityType = type,
                            durationMinutes = duration,
                            percentage = duration.toFloat() / totalDurationMinutes * PERCENT_SCALE,
                        )
                    }
                }.sortedByDescending { it.durationMinutes }

        return groupTopMixItems(items, totalDurationMinutes)
    }

    private fun groupTopMixItems(
        items: List<TrainingMixItem>,
        totalDurationMinutes: Int,
    ): List<TrainingMixItem> {
        if (items.size <= MAX_DISTINCT_MIX_TYPES) return items

        val (otherItems, nonOtherItems) = items.partition { it.activityType == WorkoutLayoutType.OTHER }
        val top = nonOtherItems.take(TOP_MIX_TYPES_BEFORE_OTHER)
        val remainder = nonOtherItems.drop(TOP_MIX_TYPES_BEFORE_OTHER)
        val otherDuration = remainder.sumOf { it.durationMinutes } + otherItems.sumOf { it.durationMinutes }
        val otherItem =
            TrainingMixItem(
                activityType = WorkoutLayoutType.OTHER,
                durationMinutes = otherDuration,
                percentage = otherDuration.toFloat() / totalDurationMinutes * PERCENT_SCALE,
            )

        return top + otherItem
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

    private const val MAX_DISTINCT_MIX_TYPES = 4
    private const val TOP_MIX_TYPES_BEFORE_OTHER = 3
    private const val PERCENT_SCALE = 100f
}

