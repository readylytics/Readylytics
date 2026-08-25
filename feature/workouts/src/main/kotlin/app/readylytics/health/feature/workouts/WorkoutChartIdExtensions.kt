package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId

@get:StringRes
val WorkoutChartId.displayNameResId: Int
    get() =
        when (this) {
            WorkoutChartId.ACWR_TRIMP -> R.string.acwr_training_load
            WorkoutChartId.WEEKLY_VOLUME_TREND -> R.string.weekly_volume_trend_title
            WorkoutChartId.WEEKLY_TRAINING -> R.string.workout_stats_weekly_title
        }
