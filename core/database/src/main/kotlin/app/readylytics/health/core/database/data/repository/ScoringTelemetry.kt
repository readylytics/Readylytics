package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig

object ScoringTelemetry {
    fun logTelemetry(
        scoringConfig: ScoringConfig,
        prefs: UserPreferences,
        rasTotalPre: Float,
        rasTotalPost: Float?,
    ) {
        val updatedAudit = scoringConfig.auditTrail.copy(
            appliedSf = scoringConfig.rasScalingFactor,
            physiologyProfile = prefs.physiologyProfile.name,
            rasTotalPre = rasTotalPre,
            rasTotalPost = rasTotalPost,
        )
        logD("ScoringConfig") { "Telemetry: $updatedAudit" }
    }
}
