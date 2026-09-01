package app.readylytics.health.core.scoring.domain.util

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Linear-interpolation percentile (numpy's default / the "R type 7" method) over an
 * already-sorted-ascending list. [p] is a fraction in `[0.0, 1.0]`. R2-DB-004: feeds the
 * warm-tier percentile sketch (`MinuteBucketAggregator`) -- SQLite has no `PERCENTILE_CONT`.
 */
fun List<Int>.percentile(p: Double): Int {
    require(isNotEmpty()) { "percentile() requires a non-empty list" }
    require(p in 0.0..1.0) { "Percentile p must be in [0.0, 1.0], but was $p" }
    // No separate size==1 / lower==upper short-circuits: when index is a whole number,
    // fraction is exactly 0.0, so the general formula already collapses to this[lower].
    val index = p * (size - 1)
    val lower = floor(index).toInt()
    val upper = ceil(index).toInt()
    val fraction = index - lower
    return (this[lower] + (this[upper] - this[lower]) * fraction).roundToInt()
}

/**
 * Bucket-aware percentile calculation (`R2-PERF-001` Level 2) over pre-sorted values and their
 * sample weights. Computes the value at percentile [p] in `[0.0, 1.0]` without expanding samples into
 * boxed collections or duplicating elements.
 */
fun weightedPercentile(
    sortedValues: IntArray,
    weights: IntArray,
    p: Double,
): Int {
    require(sortedValues.isNotEmpty()) { "Values cannot be empty" }
    require(sortedValues.size == weights.size) { "Values and weights must have identical length" }
    require(p in 0.0..1.0) { "Percentile p must be in [0.0, 1.0], but was $p" }
    val totalWeight = weights.sumOf { it.toLong() }
    val targetWeight = kotlin.math.round(p * (totalWeight - 1L)).toLong().coerceIn(0L, maxOf(0L, totalWeight - 1L))
    var runningWeight = 0L
    for (i in sortedValues.indices) {
        runningWeight += weights[i]
        if (runningWeight - 1L >= targetWeight || runningWeight >= totalWeight) {
            return sortedValues[i]
        }
    }
    return sortedValues.first()
}
