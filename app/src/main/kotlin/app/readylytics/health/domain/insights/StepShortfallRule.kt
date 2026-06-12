package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags a significant shortfall in today's step count relative to the
 * user's configured daily step goal.
 */
class StepShortfallRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val stepCount = context.today.stepCount ?: return null
        if (context.stepGoal <= 0) return null

        val threshold = context.stepGoal * InsightConstants.STEP_GOAL_SHORTFALL_RATIO
        if (stepCount >= threshold) return null

        return InsightFinding(
            type = InsightType.STEP_SHORTFALL,
            params = InsightParams.StepShortfall(stepCount = stepCount, stepGoal = context.stepGoal),
        )
    }
}
