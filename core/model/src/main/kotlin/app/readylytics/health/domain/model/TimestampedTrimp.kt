package app.readylytics.health.domain.model

data class TimestampedTrimp(
    val timestampMs: Long,
    val trimp: Float,
)
