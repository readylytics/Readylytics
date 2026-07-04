package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag

/**
 * Informs the user that sleep stage data was unavailable last night, so the
 * sleep score reflects duration and restoration only, not architecture.
 */
class StagesMissingCaveatRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (RecoveryFlag.STAGES_MISSING !in context.today.recoveryFlags) return null

        return InsightFinding(
            type = InsightType.RECOVERY_STAGES_MISSING,
            params = InsightParams.None,
        )
    }
}
