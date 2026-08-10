package app.readylytics.health.domain.model

enum class AdvisorDataConfidence {
    LOW,
    MEDIUM,
    HIGH
}

fun resolveAdvisorConfidence(
    phase: CalibrationPhase,
    hasMajorMissingSignals: Boolean,
    isEverydaySourceLowConfidence: Boolean
): AdvisorDataConfidence {
    val base = when (phase) {
        CalibrationPhase.CALIBRATION, CalibrationPhase.EARLY_BASELINE -> AdvisorDataConfidence.LOW
        CalibrationPhase.MATURING -> if (hasMajorMissingSignals) AdvisorDataConfidence.LOW else AdvisorDataConfidence.MEDIUM
        CalibrationPhase.MATURE -> if (hasMajorMissingSignals) AdvisorDataConfidence.MEDIUM else AdvisorDataConfidence.HIGH
    }

    if (isEverydaySourceLowConfidence && base == AdvisorDataConfidence.HIGH) {
        return AdvisorDataConfidence.MEDIUM
    }
    return base
}
