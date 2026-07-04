package app.readylytics.health.feature.dashboard

import androidx.annotation.StringRes
import app.readylytics.health.domain.dashboard.CardId

@get:StringRes
val CardId.displayNameResId: Int
    get() =
        when (this) {
            CardId.SLEEP_SCORE -> R.string.card_title_sleep_score
            CardId.READINESS -> app.readylytics.health.core.ui.R.string.card_title_readiness
            CardId.STEPS -> R.string.card_title_steps
            CardId.HRV -> R.string.card_title_hrv
            CardId.SLEEP_RHR -> R.string.card_title_sleep_rhr
            CardId.SLEEP_DURATION -> R.string.card_title_sleep_duration
            CardId.SLEEP_ARCHITECTURE -> R.string.card_title_sleep_architecture
            CardId.STRAIN_RATIO -> app.readylytics.health.core.ui.R.string.card_title_strain_ratio
            CardId.RAS_DAILY -> R.string.card_title_ras_daily
            CardId.CIRCADIAN_CONSISTENCY -> R.string.card_title_circadian_consistency
            CardId.RESTING_HR -> R.string.card_title_resting_hr
            CardId.RECOVERY_INDEX -> R.string.card_title_recovery_index
            CardId.ACUTE_CHRONIC_RATIO -> R.string.card_title_acute_chronic_ratio
            CardId.SLEEP_EFFICIENCY -> app.readylytics.health.core.ui.R.string.card_title_sleep_efficiency
            CardId.HEART_RATE -> R.string.card_title_heart_rate
            CardId.WEIGHT -> R.string.card_title_weight
            CardId.BODY_FAT -> R.string.card_title_body_fat
            CardId.BLOOD_PRESSURE -> R.string.card_title_blood_pressure
            CardId.OXYGEN_SATURATION -> R.string.card_title_oxygen_saturation
            CardId.INSIGHTS -> R.string.card_title_insights
        }
