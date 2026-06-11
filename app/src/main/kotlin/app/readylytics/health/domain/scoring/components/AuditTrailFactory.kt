package app.readylytics.health.domain.scoring.components

import java.time.LocalDate

object AuditTrailFactory {
    fun create(
        daysSinceInstall: Int,
        currentDate: LocalDate,
    ): AuditTrail {
        val phase = PhaseCalculator.calculatePhase(daysSinceInstall)
        return AuditTrail(
            configHashCode = 0, // Will be set by caller with actual config hash
            phaseName = phase.displayName,
            appliedAt = currentDate,
        )
    }

    fun withConfigHash(
        auditTrail: AuditTrail,
        configHashCode: Int,
    ): AuditTrail = auditTrail.copy(configHashCode = configHashCode)
}
