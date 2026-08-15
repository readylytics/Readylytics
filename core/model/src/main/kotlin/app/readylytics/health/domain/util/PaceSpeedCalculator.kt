package app.readylytics.health.domain.util

object PaceSpeedCalculator {

    const val PACE_CAP_MIN_PER_KM = 20.0

    private val PACE_EXERCISE_TYPES = setOf("56", "57", "79")

    fun isPaceActivity(exerciseType: String): Boolean = exerciseType in PACE_EXERCISE_TYPES

    fun speedMpsToSpeedKmh(speedMps: Double): Double = (speedMps * 3.6).coerceAtLeast(0.0)

    fun speedMpsToPaceMinKm(speedMps: Double): Double {
        val speedKmh = speedMpsToSpeedKmh(speedMps)
        if (speedKmh <= 0.0) return PACE_CAP_MIN_PER_KM
        return (60.0 / speedKmh).coerceAtMost(PACE_CAP_MIN_PER_KM)
    }
}
