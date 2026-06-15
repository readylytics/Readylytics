package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags a multi-night streak of below-baseline HRV readings, which can
 * indicate accumulating fatigue even before other recovery flags trigger.
 */
class HrvDeclineStreakRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val days =
            (listOf(context.today) + context.recentDays)
                .distinctBy { it.date }
                .sortedByDescending { it.date }
                .take(InsightConstants.HRV_DECLINE_STREAK_DAYS)

        if (days.size < InsightConstants.HRV_DECLINE_STREAK_DAYS) return null
        if (days.any { it.zLnHrv == null }) return null
        if (days.any { (it.zLnHrv ?: 0f) >= 0f }) return null

        return InsightFinding(
            type = InsightType.HRV_DECLINE_STREAK,
            params = InsightParams.HrvDeclineStreak(days = InsightConstants.HRV_DECLINE_STREAK_DAYS),
        )
    }
}
