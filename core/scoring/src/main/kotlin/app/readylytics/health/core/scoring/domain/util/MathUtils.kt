package app.readylytics.health.core.scoring.domain.util

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Extension functions for list math operations to improve readability and reusability.
 */

fun Float.roundToPercentInt(): Int = this.roundToInt()

fun List<Float>.mean(): Float {
    if (isEmpty()) return 0f
    return average().toFloat()
}

@JvmName("medianFloat")
fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
}

@JvmName("medianInt")
fun List<Int>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid].toFloat()
}

fun List<Float>.stdev(): Float {
    if (size < 2) return 0f
    val avg = mean()
    // Bessel's correction (n-1) for sample standard deviation
    val variance = sumOf { ((it - avg) * (it - avg)).toDouble() }.toFloat() / (size - 1)
    return sqrt(variance)
}

@JvmName("stdevInt")
fun List<Int>.stdev(): Float {
    if (size < 2) return 0f
    val avg = average().toFloat()
    val variance = sumOf { ((it - avg) * (it - avg)).toDouble() }.toFloat() / (size - 1)
    return sqrt(variance)
}

/**
 * Linear-interpolation percentile (numpy's default / the "R type 7" method) over an
 * already-sorted-ascending list. [p] is a fraction in `[0.0, 1.0]`. R2-DB-004: feeds the
 * warm-tier percentile sketch (`MinuteBucketAggregator`) -- SQLite has no `PERCENTILE_CONT`.
 */
fun List<Int>.percentile(p: Double): Int {
    require(isNotEmpty()) { "percentile() requires a non-empty list" }
    // No separate size==1 / lower==upper short-circuits: when index is a whole number,
    // fraction is exactly 0.0, so the general formula already collapses to this[lower].
    val index = p * (size - 1)
    val lower = floor(index).toInt()
    val upper = ceil(index).toInt()
    val fraction = index - lower
    return (this[lower] + (this[upper] - this[lower]) * fraction).roundToInt()
}
