package app.readylytics.health.domain.insights

import app.readylytics.health.domain.scoring.ScoringConstants

/**
 * Centralized thresholds for the deterministic insight engine rules.
 */
object InsightConstants {
    const val CIRCADIAN_BEDTIME_OFFSET_THRESHOLD_MINUTES = 90

    const val STRAIN_HIGH_RATIO_THRESHOLD = ScoringConstants.Strain.SR_SWEET_SPOT_MAX

    // Sleep duration below this fraction of the user's goal counts as a deficit.
    const val SLEEP_DEFICIT_RATIO = 0.85f
}
