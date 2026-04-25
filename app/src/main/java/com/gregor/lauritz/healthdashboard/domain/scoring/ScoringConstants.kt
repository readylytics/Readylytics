package com.gregor.lauritz.healthdashboard.domain.scoring

/**
 * Centralized constants for scoring logic to avoid magic numbers and improve maintainability.
 */
object ScoringConstants {
    // Calibration and History
    const val MIN_SESSIONS_FOR_CALIBRATION = 7
    const val ACUTE_DAYS = 7L
    const val CHRONIC_DAYS = 42L
    const val BASELINE_DAYS = 30L
    const val MATURE_DATA_TENURE_DAYS = 21

    // Defaults
    const val DEFAULT_FITNESS_LEVEL = 35f
    const val DEFAULT_GOAL_SLEEP_HOURS = 8f

    // EMA Parameters
    const val PROVISIONAL_DAYS = 21

    // Scoring Thresholds and Weights
    object Strain {
        const val NEUTRAL_SCORE = 50f
        const val OPTIMAL_LOW_SCORE = 85f
        const val OPTIMAL_SWEET_SPOT_SCORE = 100f
        const val POOR_SCORE = 30f

        const val SR_UNDER_TRAINING = 0.8f
        const val SR_SWEET_SPOT_MIN = 0.8f
        const val SR_SWEET_SPOT_MAX = 1.2f
        const val SR_OVER_TRAINING_MAX = 1.5f
    }

    object Sleep {
        const val DEEP_SLEEP_OPTIMAL_PCT = 20f
        const val REM_SLEEP_OPTIMAL_PCT = 20f

        const val WEIGHT_DURATION = 0.50f
        const val WEIGHT_ARCHITECTURE = 0.25f
        const val WEIGHT_RESTORATION = 0.25f

        const val WEIGHT_DEEP_COMPONENT = 0.5f
        const val WEIGHT_REM_COMPONENT = 0.5f

        const val DURATION_OPTIMAL_RATIO = 0.9f
        const val DURATION_NEUTRAL_RATIO = 0.8f
        const val DURATION_WARNING_RATIO = 0.7f
    }

    object Restoration {
        const val WEIGHT_HRV_SCORE = 0.5f
        const val WEIGHT_RHR_SCORE = 0.5f

        const val LATE_NADIR_PENALTY = 0.9f
        const val LATE_NADIR_THRESHOLD = 0.85f // last 15%

        const val PROVISIONAL_CV_RULE = 0.15f // 15% coefficient of variation
        const val MIN_SIGMA = 1e-6f
    }

    object Readiness {
        const val WEIGHT_RESTORATION = 0.4f
        const val WEIGHT_SLEEP = 0.3f
        const val WEIGHT_LOAD = 0.3f

        const val PARADOXICAL_HIGH_Z_HRV = 2.0f
        const val PARADOXICAL_HIGH_RHR_RATIO = 1.05f
        const val PARADOXICAL_HIGH_MAX_SCORE = 60f
    }
}
