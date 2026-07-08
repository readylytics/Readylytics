package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag

/**
 * Informs the user that last night's deep/REM sleep-stage proportions looked
 * implausible, so the sleep score reflects duration and restoration only, not
 * architecture.
 */
class SuspiciousStageRatioCaveatRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (RecoveryFlag.SUSPICIOUS_STAGE_RATIO !in context.today.recoveryFlags) return null

        return InsightFinding(
            type = InsightType.RECOVERY_SUSPICIOUS_STAGE_RATIO,
            params = InsightParams.None,
        )
    }
}
