package com.gregor.lauritz.healthdashboard.domain.model

/**
 * Display groupings for the data types Readylytics reads from Health Connect.
 * Mirrors the categories shown in the data-source settings UI.
 */
enum class HealthDataCategory(
    val displayName: String,
) {
    ACTIVITY("Activity"),
    BODY_MEASUREMENTS("Body measurements"),
    SLEEP("Sleep"),
    VITALS("Vitals"),
}

/**
 * The individual data types Readylytics fetches from Health Connect. The enum
 * [name] is the stable key used to persist per-type source-device selections
 * (see `UserPreferences.deviceByDataType`) and must not change once shipped.
 */
enum class HealthDataType(
    val displayName: String,
    val category: HealthDataCategory,
) {
    EXERCISE("Exercise", HealthDataCategory.ACTIVITY),
    STEPS("Steps", HealthDataCategory.ACTIVITY),
    BODY_FAT("Body fat", HealthDataCategory.BODY_MEASUREMENTS),
    WEIGHT("Weight", HealthDataCategory.BODY_MEASUREMENTS),
    SLEEP("Sleep", HealthDataCategory.SLEEP),
    BLOOD_PRESSURE("Blood pressure", HealthDataCategory.VITALS),
    HEART_RATE("Heart rate", HealthDataCategory.VITALS),
    HRV("Heart rate variability", HealthDataCategory.VITALS),
    OXYGEN_SATURATION("Oxygen saturation", HealthDataCategory.VITALS),
}
