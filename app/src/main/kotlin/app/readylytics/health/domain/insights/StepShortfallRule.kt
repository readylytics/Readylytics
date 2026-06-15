package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult

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
            val earliestMinutes = circadian.medianBedtimeMinutes - InsightConstants.STEP_SHORTFALL_LEAD_TIME_MINUTES
            if (context.nowMinutesOfDay < earliestMinutes) return null
        }

        return InsightFinding(
            type = InsightType.STEP_SHORTFALL,
            params = InsightParams.StepShortfall(stepCount = stepCount, stepGoal = context.stepGoal),
        )
    }
}
