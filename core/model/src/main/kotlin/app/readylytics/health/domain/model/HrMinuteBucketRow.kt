package app.readylytics.health.domain.model

/**
 * PERF-006/WP-21: one SQL-aggregated 1-minute heart-rate bucket for a scored day
 * (`HeartRateDao.getMinuteBuckets`) -- the plausibility filter (30-230 bpm) and the per-minute
 * average are already applied by the query; a bucket with no plausible samples in that minute
 * simply has no row.
 */
data class HrMinuteBucketRow(
    val bucketIndex: Int,
    val avgBpm: Double,
    val sampleCount: Int,
)
