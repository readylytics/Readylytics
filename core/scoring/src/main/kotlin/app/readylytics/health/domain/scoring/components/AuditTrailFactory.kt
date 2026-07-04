package app.readylytics.health.domain.scoring.components

import java.time.LocalDate

object AuditTrailFactory {
    private const val CALIBRATION_DAYS = 7
    private const val PROVISIONAL_DAYS = 42

    fun create(
        daysSinceInstall: Int,
        currentDate: LocalDate,
    ): AuditTrail {
        val phaseName =
            when {
                daysSinceInstall < CALIBRATION_DAYS -> "Calibrating"
                daysSinceInstall < PROVISIONAL_DAYS -> "Establishing Baseline"
                else -> "Mature"
            }
        return AuditTrail(
            configHashCode = 0, // Will be set by caller with actual config hash
            phaseName = phaseName,
            appliedAt = currentDate,
        )
    }

    fun withConfigHash(
        auditTrail: AuditTrail,
        configHashCode: Int,
    ): AuditTrail = auditTrail.copy(configHashCode = configHashCode)
}
