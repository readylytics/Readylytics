package app.readylytics.health.feature.vitals.overview

import androidx.annotation.StringRes
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.core.ui.R as CoreUiR

// Local 4-branch duplicate of feature/dashboard's CardIdExtensionsUi.kt. feature/vitals cannot
// see feature/dashboard, so this extension only covers the CardIds the Vitals tab can render.
@get:StringRes
val CardId.displayNameResId: Int
    get() =
        when (this) {
            CardId.HRV -> R.string.label_hrv_rmssd
            CardId.RESTING_HR -> R.string.label_resting_heart_rate
            CardId.OXYGEN_SATURATION -> R.string.label_oxygen_saturation
            CardId.BODY_TEMPERATURE -> CoreUiR.string.label_body_temperature
            else -> error("Unexpected CardId for Vitals: $this")
        }
