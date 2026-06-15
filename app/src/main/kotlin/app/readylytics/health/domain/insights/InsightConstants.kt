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

    const val HRV_DROP_ZSCORE_THRESHOLD = -1.5f
    const val SPO2_HYPOXIA_THRESHOLD = 94f
    const val RHR_ELEVATED_DELTA_BPM = 5f
    const val BP_SYSTOLIC_DRIFT_THRESHOLD_MMHG = 10
    const val PAI_DEPLETION_THRESHOLD = 50f
    const val PAI_DEPLETION_STRAIN_RATIO_THRESHOLD = 1.0f
    const val HRV_DECLINE_STREAK_DAYS = 3
    const val STEP_GOAL_SHORTFALL_RATIO = 0.7f
    const val PAI_WEEKLY_TARGET = 150f
    const val WEIGHT_DRIFT_PERCENT_THRESHOLD = 0.02f
    const val MIN_BP_BASELINE_SAMPLES = 3

    // The step shortfall insight only fires once we're within this many
    // minutes of the user's median bedtime, so it isn't shown too early.
    const val STEP_SHORTFALL_LEAD_TIME_MINUTES = 180

    const val LOAD_SPIKE_STRAIN_RATIO_THRESHOLD = 1.3f
    const val LOAD_SPIKE_TRIMP_THRESHOLD = 120f
    const val LOAD_SPIKE_ACWR_THRESHOLD = 1.5f
    const val LOAD_HISTORY_MIN_VALID_DAYS = 21
    const val RECOVERY_STRAIN_LOW_HRV_Z = -1.0f
    const val RECOVERY_STRAIN_ELEVATED_RHR_Z = 1.0f
    const val RECOVERY_STRAIN_RHR_DELTA_BPM = 3f
    const val RECOVERY_STRAIN_READINESS_THRESHOLD = 60f
}
