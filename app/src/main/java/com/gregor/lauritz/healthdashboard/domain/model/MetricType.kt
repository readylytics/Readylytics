package com.gregor.lauritz.healthdashboard.domain.model

/**
 * Enumeration of all available health metrics that can be displayed in widgets.
 */
enum class MetricType(val displayName: String) {
    HRV("Heart Rate Variability"),
    RHR("Resting Heart Rate"),
    SLEEP_SCORE("Sleep Score"),
    SLEEP_DURATION("Sleep Duration"),
    SLEEP_EFFICIENCY("Sleep Efficiency"),
    RECOVERY("Recovery"),
    READINESS("Readiness"),
    STRESS("Stress"),
    BODY_BATTERY("Body Battery"),
    STEPS("Steps"),
    PAI("Personal Activity Index"),
    STRAIN_RATIO("Strain Ratio"),
    CIRCADIAN_CONSISTENCY("Circadian Consistency"),
    CALORIES("Calories"),
    VO2_MAX("VO2 Max"),
    WEIGHT("Weight"),
}
