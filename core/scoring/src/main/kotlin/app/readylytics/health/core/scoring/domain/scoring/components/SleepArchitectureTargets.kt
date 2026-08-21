package app.readylytics.health.core.scoring.domain.scoring.components

import app.readylytics.health.core.scoring.domain.scoring.components.SleepArchitectureTargets

/**
 * Age-adjusted deep and REM targets as fractions of total sleep time.
 * REF: Ohayon 2004 Sleep 27:1255 — the linear fits below are anchored on that meta-analysis's
 * band values so that a birthday can no longer step the architecture sub-score.
 */
data class SleepArchitectureTargets(
    val deepPercentage: Float,
    val remPercentage: Float,
)
