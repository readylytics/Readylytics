package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.scoring.domain.util.percentile

/**
 * Groups plausibility-filtered raw heart-rate samples (already ordered by recordType, sessionId,
 * timestampMs -- see [app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
 * .getPlausibleSamplesInRangeForRollup]) into one-minute buckets, computing min/max/avg/count plus
 * the p5/p25/p50/p75/p95 percentile sketch per bucket. Replaces the pure-SQL `INSERT...SELECT`
 * rollup: SQLite has no `PERCENTILE_CONT`. Called per day-chunk by
 * [DataRollupManager.rollupDayChunk] -- see that class for why the rollup is chunked.
 */
internal fun List<HeartRateRecordEntity>.aggregateIntoMinuteBuckets(): List<HrMinuteBucketEntity> =
    groupBy { Triple((it.timestampMs / MINUTE_MS) * MINUTE_MS, it.recordType, it.sessionId ?: "") }
        .map { (key, samples) ->
            val (bucketStartMs, recordType, sessionId) = key
            val sorted = samples.map { it.beatsPerMinute }.sorted()
            HrMinuteBucketEntity(
                bucketStartMs = bucketStartMs,
                bucketEndMs = bucketStartMs + MINUTE_MS,
                minBpm = sorted.first(),
                maxBpm = sorted.last(),
                avgBpm = sorted.average(),
                sampleCount = sorted.size,
                recordType = recordType,
                sessionId = sessionId,
                deviceName = null,
                p5Bpm = sorted.percentile(P5),
                p25Bpm = sorted.percentile(P25),
                p50Bpm = sorted.percentile(P50),
                p75Bpm = sorted.percentile(P75),
                p95Bpm = sorted.percentile(P95),
            )
        }

private const val MINUTE_MS = 60_000L
private const val P5 = 0.05
private const val P25 = 0.25
private const val P50 = 0.50
private const val P75 = 0.75
private const val P95 = 0.95
