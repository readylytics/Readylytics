package app.readylytics.health.feature.sleep.overview

import androidx.annotation.StringRes
import app.readylytics.health.core.model.domain.sleep.SleepChartId
import app.readylytics.health.feature.sleep.R

@get:StringRes
val SleepChartId.displayNameResId: Int
    get() =
        when (this) {
            SleepChartId.SLEEP_DURATION_TREND -> R.string.sleep_trend_title
        }

@StringRes
fun SleepChartId.displayNameResource(): Int = displayNameResId
