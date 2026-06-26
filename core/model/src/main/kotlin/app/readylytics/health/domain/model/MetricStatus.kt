package app.readylytics.health.domain.model

enum class MetricStatus {
    NO_DATA,
    CALIBRATING,
    OPTIMAL,
    NEUTRAL,
    WARNING,
    POOR,
}
