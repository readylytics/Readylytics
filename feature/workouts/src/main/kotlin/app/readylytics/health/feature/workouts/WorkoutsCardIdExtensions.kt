package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.ui.R as CoreUiR

// Local duplicate of feature/dashboard's CardIdExtensionsUi.kt — feature/workouts cannot see
// feature/dashboard, so this only covers the CardIds the Workouts tab can render.
@get:StringRes
val CardId.displayNameResId: Int
    get() =
        when (this) {
            CardId.STRAIN_RATIO -> CoreUiR.string.card_title_strain_ratio
            CardId.READINESS -> CoreUiR.string.card_title_readiness
            CardId.RAS_DAILY -> R.string.workout_stats_ras_title
            else -> error("Unexpected CardId for Workouts: $this")
        }
