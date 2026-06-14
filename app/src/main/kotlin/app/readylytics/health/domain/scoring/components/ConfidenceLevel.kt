package app.readylytics.health.domain.scoring.components

enum class ConfidenceLevel(
    val displayName: String,
) {
    NOT_READY("Not Ready"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}
