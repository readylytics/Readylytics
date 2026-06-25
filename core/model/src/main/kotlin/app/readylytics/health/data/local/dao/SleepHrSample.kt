package app.readylytics.health.data.local.dao

data class SleepHrSample(
    val sessionId: String,
    val bpm: Int,
    val timestampMs: Long
)
