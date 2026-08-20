package app.readylytics.health.domain.airecommendation

import app.readylytics.health.core.model.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.core.scoring.domain.scoring.components.Phase

enum class AdvisorDataConfidence {
    LOW,
    MEDIUM,
    HIGH
}

fun resolveAdvisorConfidence(
    phase: Phase,
    hasMajorMissingSignals: Boolean,
    everydayLoadConfidence: LoadCoverageConfidence?,
): AdvisorDataConfidence {
    val base = when (phase) {
        Phase.CALIBRATION, Phase.EARLY_BASELINE -> AdvisorDataConfidence.LOW
        Phase.MATURING -> if (hasMajorMissingSignals) AdvisorDataConfidence.LOW else AdvisorDataConfidence.MEDIUM
        Phase.MATURE -> if (hasMajorMissingSignals) AdvisorDataConfidence.MEDIUM else AdvisorDataConfidence.HIGH
    }

    return when (everydayLoadConfidence) {
        LoadCoverageConfidence.LOW -> minOf(base, AdvisorDataConfidence.MEDIUM)
        LoadCoverageConfidence.NONE ->
            when (base) {
                AdvisorDataConfidence.HIGH -> AdvisorDataConfidence.MEDIUM
                AdvisorDataConfidence.MEDIUM, AdvisorDataConfidence.LOW -> AdvisorDataConfidence.LOW
            }

        null, LoadCoverageConfidence.MEDIUM, LoadCoverageConfidence.HIGH -> base
    }
}
