package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag

/**
 * Informs the user that today's readiness score was computed without an
 * HRV reading, so it reflects resting heart rate and sleep only.
 */
class HrvMissingCaveatRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (RecoveryFlag.HRV_MISSING !in context.today.recoveryFlags) return null

        return InsightFinding(
            type = InsightType.RECOVERY_HRV_MISSING,
            params = InsightParams.None,
        )
    }
}
