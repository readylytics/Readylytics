package app.readylytics.health.domain.util

object ElevationGainCalculator {
    const val DEFAULT_THRESHOLD_METERS = 3.0
    private const val MIN_VALID_ALTITUDE_METERS = -500.0
    private const val MAX_VALID_ALTITUDE_METERS = 9000.0
    private const val MIN_REAL_ALTITUDE_METERS = 5.0

    fun isValidAltitude(altitude: Double): Boolean =
        !altitude.isNaN() &&
            !altitude.isInfinite() &&
            altitude in MIN_VALID_ALTITUDE_METERS..MAX_VALID_ALTITUDE_METERS

    /**
     * Drops zero-altitude placeholders when the route otherwise shows real terrain. Health Connect
     * often reports exactly 0 m for points without an altitude reading; a route whose highest point
     * is meaningfully above sea level cannot also be repeatedly at 0 m, so those zeros are missing
     * data rather than genuine valleys. Routes that never rise above [MIN_REAL_ALTITUDE_METERS]
     * keep their zeros so genuinely flat routes still compute a ~zero gain.
     */
    fun filterAltitudePlaceholders(altitudes: List<Double>): List<Double> {
        val valid = altitudes.filter { isValidAltitude(it) }
        val maxNonZero = valid.filter { it > 0.0 }.maxOrNull()
        return if (maxNonZero != null && maxNonZero > MIN_REAL_ALTITUDE_METERS) {
            valid.filter { it != 0.0 }
        } else {
            valid
        }
    }

    fun calculateAscent(
        altitudes: List<Double>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
    ): Double {
        val valid = filterAltitudePlaceholders(altitudes)
        if (valid.size < 2 || thresholdMeters <= 0.0) return 0.0

        var totalGain = 0.0
        var valley = valid.first()
        var peak = valid.first()
        var isClimbing = false

        for (alt in valid) {
            if (alt > peak) {
                if (isClimbing) {
                    totalGain += alt - peak
                    peak = alt
                } else {
                    peak = alt
                    if (peak - valley >= thresholdMeters) {
                        totalGain += peak - valley
                        isClimbing = true
                    }
                }
            } else if (alt < valley) {
                valley = alt
                if (isClimbing && peak - valley >= thresholdMeters) {
                    isClimbing = false
                    peak = alt
                }
            } else {
                if (isClimbing && peak - alt >= thresholdMeters) {
                    isClimbing = false
                    valley = alt
                } else if (!isClimbing && alt - valley >= thresholdMeters) {
                    isClimbing = true
                    totalGain += alt - valley
                    peak = alt
                }
            }
        }
        return totalGain
    }
}
