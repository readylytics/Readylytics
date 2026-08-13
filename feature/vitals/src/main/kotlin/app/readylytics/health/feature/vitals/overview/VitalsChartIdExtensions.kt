package app.readylytics.health.feature.vitals.overview

import androidx.annotation.StringRes
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.core.ui.R as CoreUiR

@get:StringRes
val VitalsChartId.displayNameResId: Int
    get() =
        when (this) {
            VitalsChartId.HRV_TREND -> R.string.label_hrv_rmssd
            VitalsChartId.RHR_TREND -> R.string.label_resting_heart_rate
            VitalsChartId.SPO2_TREND -> R.string.label_oxygen_saturation
            VitalsChartId.BODY_TEMP_TREND -> CoreUiR.string.label_body_temperature
        }
