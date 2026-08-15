package app.readylytics.health.domain.util

object ElevationGainCalculator {

    const val DEFAULT_THRESHOLD_METERS = 3.0

    fun calculateAscent(
        altitudes: List<Double>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
    ): Double {
        if (altitudes.size < 2 || thresholdMeters <= 0.0) return 0.0
        var gain = 0.0
        var anchor = altitudes.first()
        for (altitude in altitudes.drop(1)) {
            if (altitude >= anchor + thresholdMeters) {
                gain += altitude - anchor
                anchor = altitude
            }
        }
        return gain
    }
}
