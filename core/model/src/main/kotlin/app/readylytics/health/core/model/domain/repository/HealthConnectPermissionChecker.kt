package app.readylytics.health.core.model.domain.repository

/**
 * Narrow permission-probe surface for UI/feature modules. Feature modules inject this
 * instead of [HealthConnectRepository] so they never see the ingestion API.
 */
interface HealthConnectPermissionChecker {
    /** Whether the optional `READ_BODY_TEMPERATURE` permission is currently granted. */
    suspend fun hasBodyTemperaturePermission(): Boolean

    /** Whether the optional `READ_STEPS` permission is currently granted. */
    suspend fun hasStepsPermission(): Boolean

    /** Whether the optional `READ_WEIGHT` permission is currently granted. */
    suspend fun hasWeightPermission(): Boolean

    /** Whether the optional `READ_DISTANCE` permission is currently granted. */
    suspend fun hasDistancePermission(): Boolean

    /** Whether the optional `READ_BODY_FAT` permission is currently granted. */
    suspend fun hasBodyFatPermission(): Boolean

    /** Whether the optional `READ_BLOOD_PRESSURE` permission is currently granted. */
    suspend fun hasBloodPressurePermission(): Boolean

    /** Whether the optional `READ_OXYGEN_SATURATION` permission is currently granted. */
    suspend fun hasOxygenSaturationPermission(): Boolean

    /** Whether the optional `READ_EXERCISE_ROUTES` permission is currently granted. */
    suspend fun hasExerciseRoutesPermission(): Boolean
}
