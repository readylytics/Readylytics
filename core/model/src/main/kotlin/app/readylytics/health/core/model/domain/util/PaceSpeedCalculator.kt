package app.readylytics.health.core.model.domain.util

object PaceSpeedCalculator {
    // Health Connect ExerciseSessionRecord type IDs:
    // 56 (Running), 57 (Running - Treadmill), 79 (Walking), 37 (Hiking).
    // These match the numeric IDs stored by HealthConnectRecordConverters; do not confuse
    // 78 (Volleyball) or 34 (Gymnastics) for Hiking.
    private val PACE_ACTIVITY_IDS = setOf("56", "57", "79", "37")

    // Fallback for sources that hand us a symbolic type instead of the numeric ID.
    private val PACE_ACTIVITY_NAMES =
        setOf("running", "running_treadmill", "walking", "hiking", "treadmill", "run", "walk", "hike")

    const val MAX_PACE_MIN_KM = 20.0
    const val PACE_CAP_MIN_PER_KM = MAX_PACE_MIN_KM

    fun isPaceActivity(exerciseType: String): Boolean {
        val trimmed = exerciseType.trim()
        if (trimmed in PACE_ACTIVITY_IDS) return true
        return trimmed.lowercase().removePrefix("exercise_type_") in PACE_ACTIVITY_NAMES
    }

    fun speedMpsToPaceMinKm(speedMps: Double): Double {
        if (speedMps <= 0.05) return MAX_PACE_MIN_KM
        val minKm = (1000.0 / speedMps) / 60.0
        return minKm.coerceAtMost(MAX_PACE_MIN_KM)
    }

    fun speedMpsToSpeedKmh(speedMps: Double): Double = (speedMps * 3.6).coerceAtLeast(0.0)
}

