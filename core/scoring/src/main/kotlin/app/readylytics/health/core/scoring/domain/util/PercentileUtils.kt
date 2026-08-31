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
