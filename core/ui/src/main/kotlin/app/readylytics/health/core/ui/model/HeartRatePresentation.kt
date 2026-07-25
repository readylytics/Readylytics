package app.readylytics.health.core.ui.model

data class HrSample(
    val timeMs: Long,
    val bpm: Int,
    val zone: Int,
)

data class HeartRateDaySummary(
    val minBpm: Int,
    val maxBpm: Int,
    val avgBpm: Int,
)
