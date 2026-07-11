package app.readylytics.health.feature.workouts

import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.scoring.WorkoutLoadLevel

internal fun WorkoutLoadLevel.labelResId(): Int =
    when (this) {
        WorkoutLoadLevel.VERY_LIGHT -> R.string.workout_intensity_very_light
        WorkoutLoadLevel.LIGHT -> R.string.workout_intensity_light
        WorkoutLoadLevel.MODERATE -> R.string.workout_intensity_moderate
        WorkoutLoadLevel.HARD -> R.string.workout_intensity_hard
        WorkoutLoadLevel.VERY_HARD -> R.string.workout_intensity_very_hard
    }

internal fun WorkoutIntensityLevel.labelResId(): Int =
    when (this) {
        WorkoutIntensityLevel.VERY_LIGHT -> R.string.workout_intensity_very_light
        WorkoutIntensityLevel.LIGHT -> R.string.workout_intensity_light
        WorkoutIntensityLevel.MODERATE -> R.string.workout_intensity_moderate
        WorkoutIntensityLevel.HARD -> R.string.workout_intensity_hard
        WorkoutIntensityLevel.VERY_HARD -> R.string.workout_intensity_very_hard
    }

internal fun WorkoutLoadLevel.metricStatus(): MetricStatus =
    when (this) {
        WorkoutLoadLevel.VERY_LIGHT -> MetricStatus.NEUTRAL
        WorkoutLoadLevel.LIGHT -> MetricStatus.NEUTRAL
        WorkoutLoadLevel.MODERATE -> MetricStatus.OPTIMAL
        WorkoutLoadLevel.HARD -> MetricStatus.WARNING
        WorkoutLoadLevel.VERY_HARD -> MetricStatus.POOR
    }

internal fun WorkoutIntensityLevel.metricStatus(): MetricStatus =
    when (this) {
        WorkoutIntensityLevel.VERY_LIGHT -> MetricStatus.NEUTRAL
        WorkoutIntensityLevel.LIGHT -> MetricStatus.NEUTRAL
        WorkoutIntensityLevel.MODERATE -> MetricStatus.OPTIMAL
        WorkoutIntensityLevel.HARD -> MetricStatus.WARNING
        WorkoutIntensityLevel.VERY_HARD -> MetricStatus.POOR
    }

internal fun WorkoutLoadLevel.badgeStatus(): MetricStatus =
    when (this) {
        WorkoutLoadLevel.VERY_LIGHT -> MetricStatus.CALIBRATING
        WorkoutLoadLevel.LIGHT -> MetricStatus.OPTIMAL
        WorkoutLoadLevel.MODERATE -> MetricStatus.OPTIMAL
        WorkoutLoadLevel.HARD -> MetricStatus.WARNING
        WorkoutLoadLevel.VERY_HARD -> MetricStatus.POOR
    }

internal fun WorkoutLoadClassification.overallStatus(): MetricStatus = finalLoad.metricStatus()

internal fun WorkoutLoadClassification.overallBadgeStatus(): MetricStatus = finalLoad.badgeStatus()

internal fun WorkoutLoadLevel.score(): Int =
    when (this) {
        WorkoutLoadLevel.VERY_LIGHT -> 1
        WorkoutLoadLevel.LIGHT -> 2
        WorkoutLoadLevel.MODERATE -> 3
        WorkoutLoadLevel.HARD -> 4
        WorkoutLoadLevel.VERY_HARD -> 5
    }

internal fun WorkoutIntensityLevel.score(): Int =
    when (this) {
        WorkoutIntensityLevel.VERY_LIGHT -> 1
        WorkoutIntensityLevel.LIGHT -> 2
        WorkoutIntensityLevel.MODERATE -> 3
        WorkoutIntensityLevel.HARD -> 4
        WorkoutIntensityLevel.VERY_HARD -> 5
    }
