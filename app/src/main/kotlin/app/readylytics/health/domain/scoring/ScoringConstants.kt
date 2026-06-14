package app.readylytics.health.domain.scoring

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

    // HRV baseline windowing — REF: Plews 2013; Buchheit 2014
    const val HRV_MU_WINDOW_DAYS = 7
    const val HRV_SIGMA_WINDOW_DAYS = 56
    const val HRV_SIGMA_BLEND_MIN_N = 7
    const val HRV_SIGMA_BLEND_MAX_N = 60

    // Valid-night input bounds — REF: Clifford 2006; Task Force 1996
    const val MIN_VALID_RMSSD_MS = 5f
    const val MAX_VALID_RMSSD_MS = 250f
    const val MIN_VALID_SLEEP_RHR = 30f
    const val MAX_VALID_SLEEP_RHR = 100f
    const val MIN_VALID_SLEEP_DURATION_MINUTES = 240

    // Sleep-stage physiological plausibility bounds — wearables frequently report impossible values
    const val MAX_VALID_DEEP_FRACTION = 0.40f
    const val MAX_VALID_REM_FRACTION = 0.45f
    const val MAX_VALID_DEEP_REM_SUM = 0.70f

    // HRV score piecewise saturation — REF: spec §4.2; Bellenger 2017 Front Physiol
    const val HRV_SCORE_SATURATION_Z = 1.5f
    const val HRV_SCORE_SATURATION_SLOPE = 0.25f

    object Workout {
        // Heart Rate Recovery (HRR) Thresholds — REF: Cole 1999 NEJM; Shetler 2001 J Am Coll Cardiol
        // Abnormal: <12 bpm (clinical), but for fitness enthusiasts 18/35 are common healthy benchmarks
        const val HRR_1MIN_OPTIMAL_THRESHOLD = 18
        const val HRR_2MIN_OPTIMAL_THRESHOLD = 35

        const val HRR_TOLERANCE_SECONDS = 15L
    }

    // Defaults
    const val DEFAULT_FITNESS_LEVEL = 35f
    const val DEFAULT_GOAL_SLEEP_HOURS = 8f
    const val DEFAULT_RHR_BPM = 60f

    // RHR baseline fallback when no measured baseline available — REF: Karvonen approximation for untrained populations
    // Estimated as: avgHR - HR_ELEVATION_AT_EXERCISE, but clamped to MINIMUM_FALLBACK_RHR for safety
    const val HR_ELEVATION_AT_EXERCISE = 20 // bpm elevation during light-moderate exercise
    const val MINIMUM_FALLBACK_RHR = 40f // physiological floor; <40 indicates measurement error or extreme bradycardia

    const val TIMEZONE_JUMP_THRESHOLD_SECONDS = 3600L

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

        // ln-scale σ floor — REF: Plews 2014 Int J Sports Physiol Perform; Schaffarczyk 2024; Kubios practice
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

    object Pai {
        const val DAILY_CAP = 75f
        const val TIER1_THRESHOLD = 50f
        const val TIER2_THRESHOLD = 100f
        const val TIER2_MULTIPLIER = 0.5f
        const val TIER3_MULTIPLIER = 0.25f

        // PAI scaling factors by physiology profile — REF: Whoop PAI model
        const val PAI_SCALING_ATHLETE = 0.15f
        const val PAI_SCALING_ACTIVE = 0.18f
        const val PAI_SCALING_SEDENTARY = 0.25f

        // Readiness integration divisor
        const val READINESS_SCALE = 100f
    }

    object Trimp {
        // Banister TRIMP coefficients — REF: Banister 1991; Morton 1990
        const val BANISTER_MALE_A = 0.64f
        const val BANISTER_MALE_B = 1.92f
        const val BANISTER_FEMALE_A = 0.86f
        const val BANISTER_FEMALE_B = 1.67f

        // Cheng LT-TRIMP — REF: Cheng et al. 1992
        // sexFactor reuses BANISTER_MALE_A / BANISTER_FEMALE_A (0.64/0.86)
        const val CHENG_BETA = 0.09f

        // iTRIMP — REF: Manzi et al. 2009
        const val ITRIMP_B = 2.1f
    }
}
