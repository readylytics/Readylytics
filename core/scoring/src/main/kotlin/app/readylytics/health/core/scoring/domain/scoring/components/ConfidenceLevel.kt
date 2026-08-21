package app.readylytics.health.core.scoring.domain.scoring.components

import app.readylytics.health.core.scoring.domain.scoring.components.ConfidenceLevel

enum class ConfidenceLevel(
    val displayName: String,
) {
    NOT_READY("Not Ready"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}
