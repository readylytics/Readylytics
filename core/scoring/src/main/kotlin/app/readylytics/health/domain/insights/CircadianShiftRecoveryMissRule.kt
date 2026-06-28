package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult

/**
 * Explains a missing rest-day recovery signal by checking whether last
 * night's bedtime deviated significantly from the user's rolling average.
 */
class CircadianShiftRecoveryMissRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (RecoveryFlag.REST_DAY_NO_IMPACT !in context.today.recoveryFlags) return null
        val circadian = context.circadianResult as? CircadianConsistencyResult.Ready ?: return null

        val offset = circadian.latestBedtimeOffsetMinutes
        if (offset <= InsightConstants.CIRCADIAN_BEDTIME_OFFSET_THRESHOLD_MINUTES) return null

        return InsightFinding(
            type = InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
            params = InsightParams.CircadianShift(bedtimeOffsetMinutes = offset),
        )
    }
}
