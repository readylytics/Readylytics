package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.domain.workouts.WorkoutChartId

@get:StringRes
val WorkoutChartId.displayNameResId: Int
    get() =
        when (this) {
            WorkoutChartId.ACWR_TRIMP -> R.string.acwr_training_load
        }
