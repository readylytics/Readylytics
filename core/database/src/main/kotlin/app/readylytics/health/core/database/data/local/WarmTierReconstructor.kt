package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import kotlin.math.round

/**
 * Rebuilds a flat, raw-like heart-rate sample stream from warm-tier 1-minute buckets. Each bucket
 * contributes [HrMinuteBucketEntity.sampleCount] samples at its rounded average, so downstream
 * percentile/TRIMP consumers that only ever see raw samples keep working unchanged once a day's
 * raw rows have been rolled up.
 */

internal fun List<HrMinuteBucketEntity>.reconstructSampleValues(): List<Int> =
    flatMap { bucket -> List(bucket.sampleCount) { round(bucket.avgBpm).toInt() } }

internal fun List<HrMinuteBucketEntity>.reconstructTimestampedSamples(): List<Pair<Long, Int>> =
    flatMap { bucket ->
        val stepMs = if (bucket.sampleCount > 1) 60_000L / bucket.sampleCount else 0L
        List(bucket.sampleCount) { i ->
            val offsetMs = (i * stepMs).coerceAtMost(59_999L)
            bucket.bucketStartMs + offsetMs to round(bucket.avgBpm).toInt()
        }
    }
