package app.readylytics.health.domain.validation

object SettingsValidators {
    val BIRTHDAY_DATE_RULE = BirthdayDateRule()

    val HRV_BASELINE_RULE = IntRangeRule(1, 500, "HRV: 1–500 ms")
    val RHR_BASELINE_RULE = IntRangeRule(30, 100, "RHR: 30–100 bpm")
    val CORE_MERGE_GAP_MINUTES_RULE = IntStepRangeRule(30, 240, 30, "Merge gap: 30–240 min in 30-minute steps")
    val SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY_RULE =
        IntStepRangeRule(840, 1380, 30, "Cutoff: 14:00–23:00 in 30-minute steps")
    val MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES_RULE =
        IntStepRangeRule(5, 60, 5, "Minimum segment: 5–60 min in 5-minute steps")
    val SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT_RULE =
        IntStepRangeRule(25, 100, 5, "Architecture coverage: 25–100% in 5% steps")

    val HEART_RATE_RULE = IntRangeRule(1, 220, "HR: 1–220 bpm")

    // Heart rate zone validation
    val HEART_RATE_ZONE_RULE = IntRangeRule(1, 220, "HR: 1–220 bpm")
    val HRR_TOLERANCE_RULE = HrrToleranceRule()

    // Height in centimeters (120–250)
    val HEIGHT_CM_RULE = FloatRangeRule(120f, 250f, "Height: 120–250 cm")

    // Resting HR percentile (1–15)
    val RESTING_HR_PERCENTILE_RULE = IntRangeRule(1, 15, "Percentile: 1–15")

    // Step goal
    val STEP_GOAL_RULE = StepGoalRule()

    // Data retention days
    val RETENTION_DAYS_RULE = RetentionDaysRule()

    // Sync interval hours (1–24)
    val SYNC_INTERVAL_HOURS_RULE = SyncIntervalHoursRule()

    // RAS scaling factor (0.1–0.3)
    val RAS_SCALING_FACTOR_RULE = RasScalingFactorRule()

    // HRV optimal/warning thresholds (0.8–1.2)
    val HRV_OPTIMAL_THRESHOLD_RULE = FloatRangeRule(1.0f, 1.2f, "HRV: 1.0–1.2")
    val HRV_WARNING_THRESHOLD_RULE = FloatRangeRule(0.8f, 1.0f, "HRV: 0.8–1.0")

    // RHR optimal/warning thresholds (0.8–1.2)
    val RHR_OPTIMAL_THRESHOLD_RULE = FloatRangeRule(0.8f, 1.0f, "RHR: 0.8–1.0")
    val RHR_WARNING_THRESHOLD_RULE = FloatRangeRule(1.0f, 1.2f, "RHR: 1.0–1.2")

    // TRIMP parameters
    val TRIMP_BANISTER_MULTIPLIER_RULE = TrimpParameterRule(0.5f, 2.5f, "Multiplier: 0.5–2.5")
    val TRIMP_CHENG_BETA_RULE = TrimpParameterRule(0.04f, 0.12f, "Beta: 0.04–0.12")
    val TRIMP_ITRIMP_B_FACTOR_RULE = TrimpParameterRule(1.0f, 4.5f, "B Factor: 1.0–4.5")

    // Domain validators for measured/calculated values
    val HRV_BOUNDS_VALIDATOR = HrvBoundsValidator()
    val RHR_BOUNDS_VALIDATOR = RhrBoundsValidator()
    val SLEEP_DURATION_VALIDATOR = SleepDurationValidator()
    val SLEEP_EFFICIENCY_VALIDATOR = SleepEfficiencyValidator()
    val SLEEP_ARCHITECTURE_VALIDATOR = SleepArchitectureValidator()
}
