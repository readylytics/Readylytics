package app.readylytics.health.domain.scoring.components

object PhaseCalculator {
    fun calculatePhase(daysSinceInstall: Int): Phase =
        when {
            daysSinceInstall < Phase.CALIBRATION_DAYS -> Phase.CALIBRATION
            daysSinceInstall < Phase.PROVISIONAL_DAYS -> Phase.PROVISIONAL
            else -> Phase.MATURE
        }
}
