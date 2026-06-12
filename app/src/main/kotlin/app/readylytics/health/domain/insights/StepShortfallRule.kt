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
            if (normalizeTimeOfDay(context.nowMinutesOfDay) < earliestMinutes) return null
        }

        return InsightFinding(
            type = InsightType.STEP_SHORTFALL,
            params = InsightParams.StepShortfall(stepCount = stepCount, stepGoal = context.stepGoal),
        )
    }

    // Mirrors CircadianConsistencyRepository's normalization: times before
    // noon are treated as "past midnight" (e.g., 1:00 AM -> 25:00) so they
    // compare correctly against a median bedtime that crosses midnight.
    private fun normalizeTimeOfDay(minutesOfDay: Int): Int =
        if (minutesOfDay < 12 * 60) minutesOfDay + 1440 else minutesOfDay
}
