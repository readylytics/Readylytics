package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.LoadSourceSelector

/**
 * Flags a rolling weekly RAS total that falls below the recommended target,
 * indicating insufficient cumulative physical activity.
 */
class RasWeeklyUnderperformanceRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val mode = context.prefs.rasSourceMode
        val days =
            (listOf(context.today) + context.recentDays)
                .distinctBy { it.date }
                .sortedByDescending { it.date }
                .take(7)
        if (days.none { LoadSourceSelector.selectTotalRas(it, mode) != null }) return null

        val weeklyRas = days.sumOf { (LoadSourceSelector.selectTotalRas(it, mode) ?: 0f).toDouble() }.toFloat()
        if (weeklyRas >= InsightConstants.RAS_WEEKLY_TARGET) return null

        return InsightFinding(
            type = InsightType.RAS_WEEKLY_UNDERPERFORMANCE,
            params =
                InsightParams.RasWeeklyShortfall(
                    weeklyRas = weeklyRas,
                    target = InsightConstants.RAS_WEEKLY_TARGET,
                ),
        )
    }
}
