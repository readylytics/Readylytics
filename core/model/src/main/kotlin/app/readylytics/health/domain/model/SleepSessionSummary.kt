package app.readylytics.health.domain.model

data class SleepSessionSummary(
    val efficiency: Float?,
    val startTime: Long,
    val endTime: Long,
)
