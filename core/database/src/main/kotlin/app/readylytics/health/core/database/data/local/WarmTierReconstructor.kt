package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Rebuilds a flat, raw-like heart-rate sample stream from warm-tier 1-minute buckets, so
 * downstream percentile/TRIMP consumers that only ever see raw samples keep working unchanged
 * once a day's raw rows have been rolled up. Three reconstruction qualities, chosen per bucket:
 *
 * 1. Percentile-sketch interpolation (buckets rolled up after the v15 migration, R2-DB-004):
 *    interpolates [HrMinuteBucketEntity.sampleCount] evenly-spaced quantile points across the
 *    7 anchors (min, p5, p25, p50, p75, p95, max).
 * 2. 3-point (`min, avg×(n-2), max`) for buckets rolled up before v15 (percentiles are `null`
 *    forever for those — rollup never reprocesses an already-rolled minute) with `sampleCount >= 3`.
 * 3. Flat mean, the original method, for `sampleCount < 3` (too few points for either richer method).
 *
 * Reconstructed values are non-decreasing within a bucket by construction (see
 * [interpolateAnchors]), so [reconstructTimestampedSamples]'s ascending-timestamp output stays
 * ascending in value too, matching what a real raw stream over a monotonic HR trend would look
 * like -- existing callers rely on this ordering.
 *
 * Note: this deliberately does not reuse `core.scoring.domain.util.percentile(p)` (also used by
 * [MinuteBucketAggregator] to build the sketch). That function assumes its input points are
 * evenly spaced across `[0, 1]` (`index = p * (size - 1)`), which holds for a plain sorted
 * sample list but not for these 7 anchors -- p5/p25/p75/p95 sit at their true quantiles
 * (0.05/0.25/0.75/0.95), not at evenly spaced 1/6 steps. Reusing it as-is would silently
 * misplace every interpolated point between p5 and p95.
 */

internal fun List<HrMinuteBucketEntity>.reconstructSampleValues(): IntArray {
    val totalCount = sumOf { it.sampleCount }
    val target = IntArray(totalCount)
    var offset = 0
    for (i in indices) {
        offset += this[i].fillBucketValues(target, offset)
    }
    return target
}

internal fun List<HrMinuteBucketEntity>.reconstructTimestampedSamples(): TimestampedSamples {
    val totalCount = sumOf { it.sampleCount }
    val timestampsMs = LongArray(totalCount)
    val bpmValues = IntArray(totalCount)
    var offset = 0
    for (bucketIndex in indices) {
        val bucket = this[bucketIndex]
        val count = bucket.sampleCount
        if (count == 0) continue

        bucket.fillBucketValues(bpmValues, offset)

        val stepMs = if (count > 1) 60_000L / count else 0L
        val startMs = bucket.bucketStartMs
        for (i in 0 until count) {
            val offsetMs = (i * stepMs).coerceAtMost(59_999L)
            timestampsMs[offset + i] = startMs + offsetMs
        }
        offset += count
    }
    return TimestampedSamples(timestampsMs, bpmValues)
}

private fun HrMinuteBucketEntity.fillBucketValues(target: IntArray, offset: Int): Int =
    when {
        p50Bpm != null -> fillFromPercentiles(target, offset)
        sampleCount >= MIN_SAMPLES_FOR_THREE_POINT -> fillThreePoint(target, offset)
        else -> fillFlatMean(target, offset)
    }

private fun HrMinuteBucketEntity.fillFlatMean(target: IntArray, offset: Int): Int {
    val mean = round(avgBpm).toInt()
    target.fill(mean, offset, offset + sampleCount)
    return sampleCount
}

private fun HrMinuteBucketEntity.fillThreePoint(target: IntArray, offset: Int): Int {
    target[offset] = minBpm
    val mean = round(avgBpm).toInt()
    if (sampleCount > 2) {
        target.fill(mean, offset + 1, offset + sampleCount - 1)
    }
    target[offset + sampleCount - 1] = maxBpm
    return sampleCount
}

private fun HrMinuteBucketEntity.fillFromPercentiles(target: IntArray, offset: Int): Int {
    val anchors =
        listOf(
            0.00 to minBpm,
            0.05 to requireNotNull(p5Bpm),
            0.25 to requireNotNull(p25Bpm),
            0.50 to requireNotNull(p50Bpm),
            0.75 to requireNotNull(p75Bpm),
            0.95 to requireNotNull(p95Bpm),
            1.00 to maxBpm,
        )
    for (i in 0 until sampleCount) {
        val quantile = (i + 0.5) / sampleCount
        target[offset + i] = interpolateAnchors(anchors, quantile)
    }
    return sampleCount
}

/**
 * Piecewise-linear interpolation over a small set of `(quantile, value)` anchors sorted ascending
 * by quantile, e.g. the 7-point warm-tier sketch. Unlike `core.scoring.domain.util.percentile(p)`
 * -- which assumes its inputs are evenly spaced across `[0, 1]` -- this walks the actual anchor
 * quantiles, so unevenly spaced anchors (p5/p25/p75/p95 vs. the evenly spaced min/median/max of a
 * plain sorted list) interpolate correctly.
 */
private fun interpolateAnchors(
    anchors: List<Pair<Double, Int>>,
    quantile: Double,
): Int {
    val clamped = quantile.coerceIn(0.0, 1.0)
    val upperIndex = anchors.indexOfFirst { it.first >= clamped }.let { if (it == -1) anchors.lastIndex else it }
    val (lowerQ, lowerV) = anchors[(upperIndex - 1).coerceAtLeast(0)]
    val (upperQ, upperV) = anchors[upperIndex]
    return when {
        upperIndex == 0 -> anchors[0].second
        upperQ == lowerQ -> upperV
        else -> (lowerV + (upperV - lowerV) * (clamped - lowerQ) / (upperQ - lowerQ)).roundToInt()
    }
}

private const val MIN_SAMPLES_FOR_THREE_POINT = 3
