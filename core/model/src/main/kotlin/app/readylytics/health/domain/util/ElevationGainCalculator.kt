package app.readylytics.health.domain.util

object ElevationGainCalculator {
    fun calculateAscent(altitudes: List<Double>, thresholdMeters: Double = 3.0): Double {
        if (altitudes.size < 2) return 0.0
        
        // 5-point moving window smoothing to ignore sensor noise, bypassed if size < 5
        val smoothed = if (altitudes.size < 5) {
            altitudes
        } else {
            altitudes.indices.map { i ->
                val start = (i - 2).coerceAtLeast(0)
                val end = (i + 2).coerceAtMost(altitudes.lastIndex)
                altitudes.subList(start, end + 1).average()
            }
        }

        var totalAscent = 0.0
        var lastBase = smoothed.first()

        for (i in 1..smoothed.lastIndex) {
            val curr = smoothed[i]
            val diff = curr - lastBase
            if (diff >= thresholdMeters) {
                totalAscent += diff
                lastBase = curr
            } else if (diff <= -thresholdMeters) {
                lastBase = curr
            }
        }
        return totalAscent
    }
}
