package com.gregor.lauritz.healthdashboard.domain.scoring

/**
 * Centralized constants for scoring logic to avoid magic numbers and improve maintainability.
 * Each non-obvious constant carries a REF tag pointing to the primary literature source.
 */
object ScoringConstants {
    // Calibration and History
    const val MIN_SESSIONS_FOR_CALIBRATION = 7
    const val ACUTE_DAYS = 7L
    const val CHRONIC_DAYS = 42L
    const val BASELINE_DAYS = 30L
    // REF: Plews 2013b Sports Med 43:773; Kubios HRV — SD estimates require ≥30 readings; 60d is industry practice
    const val MATURE_DATA_TENURE_DAYS = 60

    // Defaults
    const val DEFAULT_FITNESS_LEVEL = 35f
    const val DEFAULT_GOAL_SLEEP_HOURS = 8f

    // EMA Parameters
    const val PROVISIONAL_DAYS = 21

    object Strain {
        const val OPTIMAL_SWEET_SPOT_SCORE = 100f
        // REF: Gabbett 2016 BJSM; Windt & Gabbett 2018 BJSM — upper bound widened from 1.2 to 1.3
        const val SR_SWEET_SPOT_MAX = 1.3f
        // Smooth quadratic decay for SR > sweet spot; no artificial floor — REF: A.4 review
        const val QUADRATIC_PENALTY_K = 2.5f
    }

    object Sleep {
        // REF: Buysse 1989 PSQI; Buysse 2014 RU-SATED; Knutson 2017 NSF Sleep Health Index
        const val WEIGHT_DURATION = 0.50f
        const val WEIGHT_ARCHITECTURE = 0.25f
        const val WEIGHT_RESTORATION = 0.25f
        const val WEIGHT_DEEP_COMPONENT = 0.5f
        const val WEIGHT_REM_COMPONENT = 0.5f

        // Additive efficiency within Duration — REF: A.3; PSQI; avoids double-penalty since TST = TIB × SE
        const val WEIGHT_TST_IN_DURATION = 0.7f
        const val WEIGHT_EFF_IN_DURATION = 0.3f
        const val EFF_EXCELLENT_THRESHOLD = 90f
        const val EFF_EXCELLENT_SCORE = 100f
        const val EFF_GOOD_THRESHOLD = 85f
        const val EFF_GOOD_SCORE = 85f
        const val EFF_FAIR_THRESHOLD = 75f
        const val EFF_FAIR_SCORE = 65f
        const val EFF_POOR_THRESHOLD = 65f
        const val EFF_POOR_SCORE = 40f
        const val EFF_VERY_POOR_SCORE = 15f

        // Age-banded deep/REM saturation denominators
        // REF: Ohayon 2004 Sleep 27:1255; Boulos 2019 Lancet Respir Med 7:533
        const val DEEP_TARGET_UNDER_30 = 0.18f
        const val DEEP_TARGET_30_49    = 0.16f
        const val DEEP_TARGET_50_64    = 0.14f
        const val DEEP_TARGET_65_PLUS  = 0.12f
        const val REM_TARGET_UNDER_30  = 0.22f
        const val REM_TARGET_30_49     = 0.21f
        const val REM_TARGET_50_64     = 0.20f
        const val REM_TARGET_65_PLUS   = 0.18f

        const val DURATION_OPTIMAL_RATIO = 0.9f
        const val DURATION_NEUTRAL_RATIO = 0.8f
        const val DURATION_WARNING_RATIO = 0.7f
    }

    object Restoration {
        const val WEIGHT_HRV_SCORE = 0.5f
        const val WEIGHT_RHR_SCORE = 0.5f

        // REF: A.6; Trinder 2001 J Sleep Res 10:253 — last-third cutoff; 5% penalty pending internal validation
        const val LATE_NADIR_PENALTY = 0.95f
        const val LATE_NADIR_THRESHOLD = 0.67f

        // ln-scale σ floor and provisional CV
        // REF: Plews 2014 Int J Sports Physiol Perform; Schaffarczyk 2024; Kubios practice
        const val PROVISIONAL_CV_RULE = 0.15f
        const val MIN_LN_SIGMA = 0.04f
    }

    object Readiness {
        const val WEIGHT_RESTORATION = 0.4f
        const val WEIGHT_SLEEP = 0.3f
        const val WEIGHT_LOAD = 0.3f

        // Functional overreaching: HRV↑ + RHR↓
        // REF: Le Meur 2013 Med Sci Sports Exerc; Bellenger 2017 Front Physiol
        const val OVERREACHING_Z_HRV_THRESHOLD = 1.5f
        const val OVERREACHING_Z_RHR_THRESHOLD = -2.0f
        const val OVERREACHING_MAX_SCORE = 70f

        // Illness onset: HRV↓ + RHR↑
        // REF: Mishra 2020 Nat Biomed Eng; Quer 2021 Nat Med
        const val ILLNESS_Z_HRV_THRESHOLD = -1.5f
        const val ILLNESS_RHR_DELTA_BPM = 5f
        const val ILLNESS_MAX_SCORE = 50f
    }
}
