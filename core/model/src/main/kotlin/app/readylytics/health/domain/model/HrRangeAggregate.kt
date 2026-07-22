package app.readylytics.health.domain.model

/** PERF-005/WP-23: min/max/avg/count over a heart-rate range, computed in SQL. */
data class HrRangeAggregate(
    val minBpm: Int,
    val maxBpm: Int,
    val avgBpm: Double,
    val sampleCount: Int,
)
