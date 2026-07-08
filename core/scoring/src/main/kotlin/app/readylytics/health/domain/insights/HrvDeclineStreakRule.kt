package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
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
        // A day only counts as "below baseline" if the rounded display values the user
        // actually sees (dashboard HRV vs. baseline, both in whole ms) also disagree --
        // otherwise the dashboard would show "on baseline" while this insight claims a
        // decline, which is confusing given the tiny z-score is just float noise.
        if (days.any { !isBelowRoundedBaseline(it, context) }) return null

        return InsightFinding(
            type = InsightType.HRV_DECLINE_STREAK,
            params = InsightParams.HrvDeclineStreak(days = InsightConstants.HRV_DECLINE_STREAK_DAYS),
        )
    }

    private fun isBelowRoundedBaseline(
        day: DailySummary,
        context: InsightContext,
    ): Boolean {
        val current = day.nocturnalHrv ?: return false
        val baseline = DailyMetricsMapper.hrvBaselineRounded(day, context.prefs) ?: return false
        return current < baseline
    }
}
