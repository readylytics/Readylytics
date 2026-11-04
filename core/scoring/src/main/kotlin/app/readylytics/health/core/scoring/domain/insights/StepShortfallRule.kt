package app.readylytics.health.core.scoring.domain.insights

import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult

/**
 * Flags a significant shortfall in today's step count relative to the
 * user's configured daily step goal.
 *
 * To avoid flagging a shortfall before the user has had a chance to walk,
 * this only fires once the current time is within
 * [InsightConstants.STEP_SHORTFALL_LEAD_TIME_MINUTES] of the user's median
 * bedtime (when known).
 */
class StepShortfallRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val stepCount = context.today.stepCount ?: return null
        if (context.stepGoal <= 0) return null

        val threshold = context.stepGoal * InsightConstants.STEP_GOAL_SHORTFALL_RATIO
        if (stepCount >= threshold) return null

        val circadian = context.circadianResult
        if (circadian is CircadianConsistencyResult.Ready) {
            // Bedtime is a clock minute in [0, 1440); a "past midnight" bedtime (before noon) is lifted
            // into the next-day evening space so it stays comparable with nowMinutesOfDay.
            val bedtime = circadian.medianBedtimeMinutes
            val bedtimeInEveningSpace = if (bedtime < NOON_MINUTES) bedtime + MINUTES_PER_DAY else bedtime
            val earliestMinutes = bedtimeInEveningSpace - InsightConstants.STEP_SHORTFALL_LEAD_TIME_MINUTES
            if (context.nowMinutesOfDay < earliestMinutes) return null
        }

        return InsightFinding(
            type = InsightType.STEP_SHORTFALL,
            params = InsightParams.StepShortfall(stepCount = stepCount, stepGoal = context.stepGoal),
        )
    }

    private companion object {
        const val NOON_MINUTES = 12 * 60
        const val MINUTES_PER_DAY = 24 * 60
    }
}
