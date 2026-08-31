package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange

@get:StringRes
val FatigueCurveRange.labelResId: Int
    get() =
        when (this) {
            FatigueCurveRange.ONE_DAY -> R.string.fatigue_range_one_day
            FatigueCurveRange.THREE_DAYS -> R.string.fatigue_range_three_days
            FatigueCurveRange.SEVEN_DAYS -> R.string.fatigue_range_seven_days
        }
