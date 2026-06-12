package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags a rolling weekly PAI total that falls below the recommended target,
 * indicating insufficient cumulative physical activity.
 */
class PaiWeeklyUnderperformanceRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val days = (listOf(context.today) + context.recentDays).distinctBy { it.date }
        if (days.none { it.totalPai != null }) return null

        val weeklyPai = days.sumOf { (it.totalPai ?: 0f).toDouble() }.toFloat()
        if (weeklyPai >= InsightConstants.PAI_WEEKLY_TARGET) return null

        return InsightFinding(
            type = InsightType.PAI_WEEKLY_UNDERPERFORMANCE,
            params =
                InsightParams.PaiWeeklyShortfall(
                    weeklyPai = weeklyPai,
                    target = InsightConstants.PAI_WEEKLY_TARGET,
                ),
        )
    }
}
