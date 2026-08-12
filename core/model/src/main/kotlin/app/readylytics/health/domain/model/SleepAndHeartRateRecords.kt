package app.readylytics.health.domain.model

data class HeartRateRecord(
    val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

data class SleepSession(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val efficiency: Float,
    val deepSleepMinutes: Int,
    val remSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Float? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
    val deviceName: String? = null,
)

data class SleepHrSample(
    val sessionId: String,
    val beatsPerMinute: Int,
)
