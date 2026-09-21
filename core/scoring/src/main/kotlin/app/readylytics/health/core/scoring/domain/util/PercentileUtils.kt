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

/**
 * Weighted form of [List.percentile]: the exact R-type-7 linear-interpolation percentile of the
 * distribution `sortedValues` repeated `weights` times, computed without materializing the
 * expanded list. For any distribution, `weightedPercentileR7(values, counts, p)` equals
 * `expandedList.percentile(p)`.
 *
 * Distinct from [weightedPercentile], which selects the value at the *rounded* weighted rank
 * (nearest-rank, no interpolation between neighbouring values) and is the convention
 * `BaselineComputer`/`HistoricalSleepDayAssembler` already publish. WP-17 needs the interpolating
 * form: warm-tier minute percentiles are currently stored by `MinuteBucketAggregator` via
 * `List<Int>.percentile`, so reading them back out of a `bpmHistogram` must reproduce the same
 * numbers rather than shift a scoring input.
 */
fun weightedPercentileR7(
    sortedValues: IntArray,
    weights: IntArray,
    p: Double,
): Int {
    require(sortedValues.isNotEmpty()) { "Values cannot be empty" }
    require(sortedValues.size == weights.size) { "Values and weights must have identical length" }
    require(p in 0.0..1.0) { "Percentile p must be in [0.0, 1.0], but was $p" }
    val totalWeight = weights.sumOf { it.toLong() }
    if (totalWeight <= 0L) return sortedValues.first()
    val index = p * (totalWeight - 1L)
    val lowerRank = floor(index).toLong()
    val fraction = index - lowerRank
    val lowerValue = valueAtWeightedRank(sortedValues, weights, lowerRank)
    val upperValue = valueAtWeightedRank(sortedValues, weights, ceil(index).toLong())
    return (lowerValue + (upperValue - lowerValue) * fraction).roundToInt()
}

/** Value occupying position [rank] (0-based) of the weight-expanded distribution. */
private fun valueAtWeightedRank(
    sortedValues: IntArray,
    weights: IntArray,
    rank: Long,
): Int {
    var cumulative = 0L
    for (i in sortedValues.indices) {
        cumulative += weights[i]
        if (rank < cumulative) return sortedValues[i]
    }
    return sortedValues.last()
}
