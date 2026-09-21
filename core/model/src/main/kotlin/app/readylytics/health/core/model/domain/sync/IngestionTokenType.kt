package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType

/**
 * Token type registry for Health Connect Changes API ingestion.
 *
 * Keeps interval types (DISTANCE, ELEVATION_GAINED) separate from display [HealthDataType]
 * so that OD-4 gate requirements are respected without adding unrequested source controls
 * or device selection options in settings or UI.
 */
enum class IngestionTokenType(
    val stableName: String,
    val displayType: HealthDataType? = null,
    val intervalKind: IntervalKind? = null,
) {
    SLEEP("SLEEP", displayType = HealthDataType.SLEEP),
    HEART_RATE("HEART_RATE", displayType = HealthDataType.HEART_RATE),
    HRV("HRV", displayType = HealthDataType.HRV),
    EXERCISE("EXERCISE", displayType = HealthDataType.EXERCISE),
    WEIGHT("WEIGHT", displayType = HealthDataType.WEIGHT),
    BODY_FAT("BODY_FAT", displayType = HealthDataType.BODY_FAT),
    BLOOD_PRESSURE("BLOOD_PRESSURE", displayType = HealthDataType.BLOOD_PRESSURE),
    OXYGEN_SATURATION("OXYGEN_SATURATION", displayType = HealthDataType.OXYGEN_SATURATION),
    BODY_TEMPERATURE("BODY_TEMPERATURE", displayType = HealthDataType.BODY_TEMPERATURE),
    STEPS("STEPS", displayType = HealthDataType.STEPS),
    VO2_MAX("VO2_MAX", displayType = HealthDataType.VO2_MAX),
    DISTANCE("DISTANCE", intervalKind = IntervalKind.DISTANCE),
    ELEVATION_GAINED("ELEVATION_GAINED", intervalKind = IntervalKind.ELEVATION_GAINED);

    val tokenKey: String
        get() = stableName

    companion object {
        fun from(dataType: HealthDataType): IngestionTokenType =
            entries.first { it.displayType == dataType }

        fun from(kind: IntervalKind): IngestionTokenType =
            entries.first { it.intervalKind == kind }

        fun fromHealthDataType(dataType: HealthDataType): IngestionTokenType = from(dataType)
    }
}
