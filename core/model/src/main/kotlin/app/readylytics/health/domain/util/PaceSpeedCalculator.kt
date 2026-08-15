package app.readylytics.health.domain.util

object PaceSpeedCalculator {
    // Health Connect exercise type IDs: 56 (Running), 57 (Running - Treadmill), 79 (Walking), 78 (Hiking), 34 (Hiking)
    private val PACE_ACTIVITY_IDS = setOf("56", "57", "79", "78", "34")
    private val PACE_ACTIVITY_NAMES = setOf("running", "walking", "hiking", "treadmill", "run", "walk", "hike")

    const val MAX_PACE_MIN_KM = 20.0
    const val PACE_CAP_MIN_PER_KM = MAX_PACE_MIN_KM

    fun isPaceActivity(exerciseType: String): Boolean {
        val trimmed = exerciseType.trim().lowercase()
        return exerciseType.trim() in PACE_ACTIVITY_IDS || trimmed in PACE_ACTIVITY_NAMES
    }

    fun speedMpsToPaceMinKm(speedMps: Double): Double {
        if (speedMps <= 0.05) return MAX_PACE_MIN_KM
        val minKm = (1000.0 / speedMps) / 60.0
        return minKm.coerceAtMost(MAX_PACE_MIN_KM)
    }

    fun speedMpsToSpeedKmh(speedMps: Double): Double = (speedMps * 3.6).coerceAtLeast(0.0)
}

