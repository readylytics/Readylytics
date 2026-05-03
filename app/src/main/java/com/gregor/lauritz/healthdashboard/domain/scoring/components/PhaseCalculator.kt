package com.gregor.lauritz.healthdashboard.domain.scoring.components

object PhaseCalculator {
    fun calculatePhase(daysSinceInstall: Int): Phase {
        return when {
            daysSinceInstall < Phase.CALIBRATION_DAYS -> Phase.CALIBRATION
            daysSinceInstall < Phase.PROVISIONAL_DAYS -> Phase.PROVISIONAL
            else -> Phase.MATURE
        }
    }
}
