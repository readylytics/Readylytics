package app.readylytics.health.core.model.domain.model

data class TimestampedTrimp(
    val timestampMs: Long,
    val trimp: Float,
)
