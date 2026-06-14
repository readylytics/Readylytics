package app.readylytics.health.domain.scoring.components

object PhaseCalculator {
    fun calculatePhase(totalValidHrvNights: Int): Phase =
        when {
            totalValidHrvNights <= Phase.CALIBRATION_MAX_SESSIONS -> Phase.CALIBRATION
            totalValidHrvNights <= Phase.EARLY_BASELINE_MAX_SESSIONS -> Phase.EARLY_BASELINE
            totalValidHrvNights <= Phase.MATURING_MAX_SESSIONS -> Phase.MATURING
            else -> Phase.MATURE
        }
}
