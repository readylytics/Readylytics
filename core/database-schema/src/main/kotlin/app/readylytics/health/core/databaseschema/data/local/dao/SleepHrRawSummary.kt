package app.readylytics.health.core.databaseschema.data.local.dao

data class SleepHrRawSummary(
    val sessionId: String,
    val sumBpm: Long,
    val sampleCount: Long,
)
