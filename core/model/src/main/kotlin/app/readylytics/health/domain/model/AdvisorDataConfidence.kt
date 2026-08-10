package app.readylytics.health.domain.model

import app.readylytics.health.domain.scoring.LoadCoverageConfidence

enum class AdvisorDataConfidence {
    LOW,
    MEDIUM,
    HIGH
}

fun resolveAdvisorConfidence(
    phase: CalibrationPhase,
    hasMajorMissingSignals: Boolean,
    everydayLoadConfidence: LoadCoverageConfidence?,
): AdvisorDataConfidence {
    val base = when (phase) {
        CalibrationPhase.CALIBRATION, CalibrationPhase.EARLY_BASELINE -> AdvisorDataConfidence.LOW
        CalibrationPhase.MATURING -> if (hasMajorMissingSignals) AdvisorDataConfidence.LOW else AdvisorDataConfidence.MEDIUM
        CalibrationPhase.MATURE -> if (hasMajorMissingSignals) AdvisorDataConfidence.MEDIUM else AdvisorDataConfidence.HIGH
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
