package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.domain.workouts.WorkoutHistoryId

@get:StringRes
val WorkoutHistoryId.displayNameResId: Int
    get() =
        when (this) {
            WorkoutHistoryId.WORKOUT_LIST -> R.string.workout_stats_history_title
        }
