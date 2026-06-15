package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Explains a suppressed autonomic response (HRV down, RHR up) by correlating
 * it with high acute-vs-chronic strain combined with a sleep deficit.
 */
class HighStrainSleepDeficitRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val strainRatio = context.today.strainRatio ?: return null
        if (strainRatio <= InsightConstants.STRAIN_HIGH_RATIO_THRESHOLD) return null

        val sleepDurationMinutes = context.today.sleepDurationMinutes ?: return null
        val deficitThreshold = context.goalSleepMinutes * InsightConstants.SLEEP_DEFICIT_RATIO
        if (sleepDurationMinutes >= deficitThreshold) return null

        val rhrDelta = context.today.readinessResult.diagnostics.rhrDeltaBpm
        val hasRecoveryMarker =
            (context.today.zLnHrv ?: 0f) <= InsightConstants.RECOVERY_STRAIN_LOW_HRV_Z ||
                (context.today.zRhr ?: 0f) >= InsightConstants.RECOVERY_STRAIN_ELEVATED_RHR_Z ||
                (rhrDelta ?: 0f) >= InsightConstants.RECOVERY_STRAIN_RHR_DELTA_BPM ||
                (context.today.readinessScore ?: 100f) < InsightConstants.RECOVERY_STRAIN_READINESS_THRESHOLD
        if (!hasRecoveryMarker) return null

        return InsightFinding(
            type = InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
            params =
                InsightParams.HighStrainSleepDeficit(
                    strainRatio = strainRatio,
                    sleepDeficitMinutes = context.goalSleepMinutes - sleepDurationMinutes,
                ),
        )
    }
}
