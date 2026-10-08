package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.util.UnitConverter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * The three distance-on-x workout charts. They share one renderer ([DistanceSeriesChart]); this
 * enum carries everything that differs between them.
 */
internal enum class DistanceSeriesKind(
    /** Y padding (display units) added above the max and below the min, floored at 0. */
    val yMargin: Double,
    val areaAlpha: Float,
    val testTag: String,
    /** Multiplier from the stored metric value to its imperial display unit. */
    val imperialFactor: Double,
) {
    PACE(
        yMargin = 0.5,
        areaAlpha = 0.3f,
        testTag = "PaceSpeedChartCanvas",
        imperialFactor = UnitConverter.MI_PER_KM.toDouble(),
    ),
    SPEED(
        yMargin = 1.0,
        areaAlpha = 0.3f,
        testTag = "PaceSpeedChartCanvas",
        imperialFactor = UnitConverter.KM_TO_MI.toDouble(),
    ),
    ELEVATION(
        yMargin = 5.0,
        areaAlpha = 0.35f,
        testTag = "ElevationChartCanvas",
        imperialFactor = UnitConverter.METERS_TO_FEET.toDouble(),
    ),
}

private const val DISTANCE_PRECISION = 1000.0
private const val SECONDS_PER_MINUTE = 60
private const val MAX_DISPLAY_SECONDS = 59
private const val WHOLE_TICK_DISTANCE = 10.0

/** Converts raw (km, metric value) samples to display units, dropping non-finite values. */
internal fun toDisplaySeries(
    raw: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    unitSystem: UnitSystem,
): List<Pair<Double, Double>> {
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val distanceFactor = if (imperial) UnitConverter.KM_TO_MI.toDouble() else 1.0
    val valueFactor = if (imperial) kind.imperialFactor else 1.0
    return raw.mapNotNull { (distanceKm, value) ->
        if (!value.isFinite()) return@mapNotNull null
        (round(distanceKm * distanceFactor * DISTANCE_PRECISION) / DISTANCE_PRECISION) to value * valueFactor
    }
}

/** Axis/tooltip value text without unit: pace `m:ss`, speed one decimal, elevation whole. */
internal fun formatSeriesValue(
    kind: DistanceSeriesKind,
    value: Double,
): String =
    when (kind) {
        DistanceSeriesKind.PACE -> {
            val minutes = value.toInt()
            val seconds = ((value - minutes) * SECONDS_PER_MINUTE).roundToInt().coerceIn(0, MAX_DISPLAY_SECONDS)
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
        DistanceSeriesKind.SPEED -> String.format(Locale.getDefault(), "%.1f", value)
        DistanceSeriesKind.ELEVATION -> String.format(Locale.getDefault(), "%.0f", value)
    }

internal fun formatPointValue(
    kind: DistanceSeriesKind,
    value: Double,
    unit: String,
): String = "${formatSeriesValue(kind, value)} $unit"

internal fun formatPointDistance(
    distance: Double,
    unit: String,
): String = String.format(Locale.getDefault(), "%.2f %s", distance, unit)

internal fun formatDistanceTick(
    value: Double,
    maxDistance: Double,
): String = String.format(Locale.getDefault(), if (maxDistance < WHOLE_TICK_DISTANCE) "%.1f" else "%.0f", value)

/** `[min − margin floored at 0, max + margin]`; an empty series gives `[0, margin]`. */
internal fun seriesYRange(
    values: List<Double>,
    margin: Double,
): ClosedFloatingPointRange<Double> {
    val lo = values.minOrNull() ?: 0.0
    val hi = values.maxOrNull() ?: 0.0
    return (lo - margin).coerceAtLeast(0.0)..(hi + margin)
}

internal fun computeDistanceLabels(
    maxDistance: Double,
    target: Int = 5,
): List<Double> {
    if (maxDistance <= 0.0) return listOf(0.0)
    val intervals = (target - 1).coerceAtLeast(1)
    val step = maxDistance / intervals
    return (0..intervals).map { (it * step) }.distinct()
}

internal fun nearestPointIndex(
    points: List<Pair<Double, Double>>,
    x: Double,
): Int = points.indices.minByOrNull { abs(points[it].first - x) } ?: 0

/** TalkBack "previous point": from nothing or the first point, wrap to the last. */
internal fun previousPointIndex(
    current: Int?,
    lastIndex: Int,
): Int {
    val index = current ?: -1
    return if (index > 0) index - 1 else lastIndex
}

/** TalkBack "next point": from nothing or the last point, wrap to the first. */
internal fun nextPointIndex(
    current: Int?,
    lastIndex: Int,
): Int {
    val index = current ?: -1
    return if (index != -1 && index < lastIndex) index + 1 else 0
}
