package app.readylytics.health.core.model.domain.model

data class SleepSessionSummary(
    val efficiency: Float?,
    val startTime: Long,
    val endTime: Long,
)
