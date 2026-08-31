package app.readylytics.health.core.model.domain.util

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
        val hasRealTerrain = hasRealTerrain(series)
        val isReal = { alt: Double -> isValidAltitude(alt) && (!hasRealTerrain || alt != 0.0) }
        val realPoints =
            series.mapIndexedNotNull { i, (dist, alt) ->
                if (alt != null && isReal(alt)) Triple(i, dist, alt) else null
            }

        return when {
            realPoints.isEmpty() -> emptyList()
            realPoints.size < 2 -> realPoints.map { it.second to it.third }
            else -> buildSmoothedSeries(series, realPoints, isReal)
        }
    }

    private fun hasRealTerrain(series: List<Pair<Double, Double?>>): Boolean =
        series
            .mapNotNull { (_, alt) -> alt }
            .filter { isValidAltitude(it) }
            .filter { it > 0.0 }
            .maxOrNull()
            ?.let { it > MIN_REAL_ALTITUDE_METERS } ?: false

    private fun buildSmoothedSeries(
        series: List<Pair<Double, Double?>>,
        realPoints: List<Triple<Int, Double, Double>>,
        isReal: (Double) -> Boolean,
    ): List<Pair<Double, Double>> {
        val result = mutableListOf<Pair<Double, Double>>()
        var nextRealIdx = 0
        for (i in series.indices) {
            val (dist, alt) = series[i]
            if (alt != null && isReal(alt)) {
                result.add(dist to alt)
            } else {
                while (nextRealIdx < realPoints.size && realPoints[nextRealIdx].first <= i) {
                    nextRealIdx++
                }
                interpolatePoint(dist, realPoints.getOrNull(nextRealIdx - 1), realPoints.getOrNull(nextRealIdx))
                    ?.let { result.add(it) }
            }
        }
        return result
    }

    private fun interpolatePoint(
        dist: Double,
        prev: Triple<Int, Double, Double>?,
        next: Triple<Int, Double, Double>?,
    ): Pair<Double, Double>? =
        if (prev != null && next != null && next.second != prev.second) {
            val frac = (dist - prev.second) / (next.second - prev.second)
            val interpolated = prev.third + (next.third - prev.third) * frac
            if (!interpolated.isNaN() && !interpolated.isInfinite()) {
                dist to interpolated
            } else {
                null
            }
        } else {
            null
        }

    fun calculateAscent(
        altitudes: List<Double>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
    ): Double {
        val valid = filterAltitudePlaceholders(altitudes)
        if (valid.size < 2 || thresholdMeters <= 0.0) return 0.0

        var state = AscentTracker(valley = valid.first(), peak = valid.first(), isClimbing = false)
        for (alt in valid) {
            state = updateAscentState(state, alt, thresholdMeters)
        }
        return state.totalGain
    }
}

private data class AscentTracker(
    val totalGain: Double = 0.0,
    val valley: Double,
    val peak: Double,
    val isClimbing: Boolean,
)

private fun updateAscentState(
    state: AscentTracker,
    alt: Double,
    thresholdMeters: Double,
): AscentTracker =
    when {
        alt > state.peak -> handlePeakRise(state, alt, thresholdMeters)
        alt < state.valley -> handleValleyDrop(state, alt, thresholdMeters)
        else -> handleMidElevation(state, alt, thresholdMeters)
    }

private fun handlePeakRise(
    state: AscentTracker,
    alt: Double,
    thresholdMeters: Double,
): AscentTracker =
    if (state.isClimbing) {
        state.copy(totalGain = state.totalGain + (alt - state.peak), peak = alt)
    } else if (alt - state.valley >= thresholdMeters) {
        state.copy(
            totalGain = state.totalGain + (alt - state.valley),
            peak = alt,
            isClimbing = true,
        )
    } else {
        state.copy(peak = alt)
    }

private fun handleValleyDrop(
    state: AscentTracker,
    alt: Double,
    thresholdMeters: Double,
): AscentTracker =
    if (state.isClimbing && state.peak - alt >= thresholdMeters) {
        state.copy(valley = alt, peak = alt, isClimbing = false)
    } else {
        state.copy(valley = alt)
    }

private fun handleMidElevation(
    state: AscentTracker,
    alt: Double,
    thresholdMeters: Double,
): AscentTracker =
    when {
        state.isClimbing && state.peak - alt >= thresholdMeters ->
            state.copy(isClimbing = false, valley = alt)
        !state.isClimbing && alt - state.valley >= thresholdMeters ->
            state.copy(
                isClimbing = true,
                totalGain = state.totalGain + (alt - state.valley),
                peak = alt,
            )
        else -> state
    }
