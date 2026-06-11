package app.readylytics.health.domain.scoring.components

enum class Phase(
    val displayName: String,
) {
    CALIBRATION("Calibrating"),
    PROVISIONAL("Establishing Baseline"),
    MATURE("Mature"),
    ;

    companion object {
        const val CALIBRATION_DAYS = 7
        const val PROVISIONAL_DAYS = 42
    }
}
