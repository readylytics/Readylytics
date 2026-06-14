package app.readylytics.health.domain.scoring.components

enum class Phase(
    val displayName: String,
    val confidence: ConfidenceLevel,
) {
    CALIBRATION("Calibrating", ConfidenceLevel.NOT_READY),
    EARLY_BASELINE("Early Baseline", ConfidenceLevel.LOW),
    MATURING("Maturing", ConfidenceLevel.MEDIUM),
    MATURE("Mature", ConfidenceLevel.HIGH),
    ;

    companion object {
        const val CALIBRATION_MAX_SESSIONS = 6
        const val EARLY_BASELINE_MAX_SESSIONS = 20
        const val MATURING_MAX_SESSIONS = 59
    }
}
