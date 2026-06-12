package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag

/**
 * Explains a suppressed autonomic response (HRV down, RHR up) by correlating
 * it with high acute-vs-chronic strain combined with a sleep deficit.
 */
class HighStrainSleepDeficitRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (RecoveryFlag.ILLNESS_ONSET !in context.today.recoveryFlags) return null

        val strainRatio = context.today.strainRatio ?: return null
        if (strainRatio <= InsightConstants.STRAIN_HIGH_RATIO_THRESHOLD) return null

        val sleepDurationMinutes = context.today.sleepDurationMinutes ?: return null
        val deficitThreshold = context.goalSleepMinutes * InsightConstants.SLEEP_DEFICIT_RATIO
        if (sleepDurationMinutes >= deficitThreshold) return null

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
