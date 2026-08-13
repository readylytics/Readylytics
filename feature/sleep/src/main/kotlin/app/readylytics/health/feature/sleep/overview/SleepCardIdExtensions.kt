package app.readylytics.health.feature.sleep.overview

import androidx.annotation.StringRes
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R

@get:StringRes
val SleepTopCardId.displayNameResId: Int
    get() =
        when (this) {
            SleepTopCardId.SLEEP_SCORE -> R.string.sleep_score_gauge_title
            SleepTopCardId.SLEEP_DURATION_GAUGE -> R.string.sleep_time_gauge_title
            SleepTopCardId.SLEEP_BREAKDOWN_BAR -> R.string.sleep_breakdown_title
            SleepTopCardId.SLEEP_STAGES_TIMELINE -> R.string.sleep_timeline_title
            SleepTopCardId.SLEEP_HR_CHART -> R.string.sleep_hr_chart_title
        }

@StringRes
fun SleepTopCardId.displayNameResource(): Int = displayNameResId

@get:StringRes
val SleepMetricCardId.displayNameResId: Int
    get() =
        when (this) {
            SleepMetricCardId.CIRCADIAN_CONSISTENCY -> R.string.sleep_card_title_circadian_consistency
            SleepMetricCardId.SLEEP_EFFICIENCY -> R.string.sleep_card_title_sleep_efficiency
            SleepMetricCardId.DEEP_SLEEP -> R.string.card_title_deep_sleep
            SleepMetricCardId.REM_SLEEP -> R.string.card_title_rem_sleep
            SleepMetricCardId.NAP_DURATION -> R.string.card_title_nap_duration
            SleepMetricCardId.NAP_COUNT -> R.string.card_title_nap_count
        }

@StringRes
fun SleepMetricCardId.displayNameResource(): Int = displayNameResId
