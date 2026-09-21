package app.readylytics.health.core.scoring.domain.insights

import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.RecoveryFlag

/**
 * Informs the user that today's readiness score was computed without an HRV reading. C3 (WP-13)
 * widened [RecoveryFlag.HRV_MISSING]'s trigger beyond "sleep recorded but no HRV" to also cover
 * "no sleep recorded at all" (see `ReadinessSummaryCoordinator.withAbsentSleepDiagnostics`), so
 * this caveat's copy (`insight_recovery_hrv_missing_*`) deliberately says nothing about what other
 * data the score *does* include -- that can differ between the two trigger conditions.
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
