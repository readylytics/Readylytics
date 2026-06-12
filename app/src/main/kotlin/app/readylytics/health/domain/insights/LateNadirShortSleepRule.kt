package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Explains a suppressed restoration score by correlating a delayed HR nadir
 * with a sleep duration shortfall against the user's goal.
 */
class LateNadirShortSleepRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (context.today.readinessResult.diagnostics.lateNadir != true) return null

        val sleepDurationMinutes = context.today.sleepDurationMinutes ?: return null
        val deficitThreshold = context.goalSleepMinutes * InsightConstants.SLEEP_DEFICIT_RATIO
        if (sleepDurationMinutes >= deficitThreshold) return null

        return InsightFinding(
            type = InsightType.LATE_NADIR_SHORT_SLEEP,
            params =
                InsightParams.LateNadirShortSleep(
                    sleepDurationMinutes = sleepDurationMinutes,
                    goalSleepMinutes = context.goalSleepMinutes,
                ),
        )
    }
}
