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

    /**
     * Smooths an (cumulative-distance, altitude) profile for display. Points whose altitude is null
     * or a zero-altitude placeholder get linearly interpolated between the nearest real readings, so
     * the chart shows a continuous profile instead of diving to 0 m wherever Health Connect lacked an
     * altitude reading. Leading/trailing points without a real anchor are dropped. The interpolated
     * values lie on the straight segments between real readings, so gain computation should keep
     * using [filterAltitudePlaceholders] over the raw altitudes.
     */
    fun smoothElevationProfile(series: List<Pair<Double, Double?>>): List<Pair<Double, Double>> {
        val realTerrain =
            series
                .mapNotNull { (_, alt) -> alt }
                .filter { isValidAltitude(it) }
                .filter { it > 0.0 }
                .maxOrNull()
                ?.let { it > MIN_REAL_ALTITUDE_METERS } ?: false
        val isReal = { alt: Double -> isValidAltitude(alt) && (!realTerrain || alt != 0.0) }
        val realPoints =
            series.mapIndexedNotNull { i, (dist, alt) ->
                if (alt != null && isReal(alt)) Triple(i, dist, alt) else null
            }
        if (realPoints.isEmpty()) return emptyList()
        if (realPoints.size < 2) {
            return realPoints.map { it.second to it.third }
        }
        val result = mutableListOf<Pair<Double, Double>>()
        var nextRealIdx = 0
        for (i in series.indices) {
            val (dist, alt) = series[i]
            if (alt != null && isReal(alt)) {
                result.add(dist to alt)
                continue
            }
            while (nextRealIdx < realPoints.size && realPoints[nextRealIdx].first <= i) nextRealIdx++
            val next = realPoints.getOrNull(nextRealIdx)
            val prev = realPoints.getOrNull(nextRealIdx - 1)
            if (prev == null || next == null) continue
            val spread = next.second - prev.second
            if (spread == 0.0) continue
            val frac = (dist - prev.second) / spread
            val interpolated = prev.third + (next.third - prev.third) * frac
            if (interpolated.isNaN() || interpolated.isInfinite()) continue
            result.add(dist to interpolated)
        }
        return result
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
