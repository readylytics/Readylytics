package app.readylytics.health.core.databaseschema.data.local.dao

data class SleepHrSample(
    val sessionId: String,
    val beatsPerMinute: Int,
)
