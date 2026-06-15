package app.readylytics.health.domain.insights.detail

import app.readylytics.health.domain.model.InsightType

class InsightCauseRanker {
    fun rankCauses(
        insightId: InsightType,
        context: DailyInsightContext,
        causes: List<InsightCause>,
    ): List<InsightCause> =
        causes
            .mapIndexed { index, cause -> RankedCause(cause, score(insightId, context, cause.rankHint), index) }
            .sortedWith(compareByDescending<RankedCause> { it.score }.thenBy { it.index })
            .map { it.cause }

    private fun score(
        insightId: InsightType,
        context: DailyInsightContext,
        hint: CauseRankHint,
    ): Int {
        var score = 0
        if (hint == CauseRankHint.LATE_WORKOUT &&
            context.lastWorkoutEndedMinutesBeforeSleep != null &&
            context.lastWorkoutEndedMinutesBeforeSleep <= 240
        ) {
            score += 100
        }

        if (hint == CauseRankHint.HIGH_STRAIN_RATIO &&
            context.strainRatio != null &&
            context.strainRatio > 1.3f
        ) {
            score += 100
        }

        if (hint == CauseRankHint.HIGH_TRIMP_YESTERDAY &&
            context.yesterdayTrimp != null &&
            context.yesterdayTrimp >= 120f
        ) {
            score += 100
        }

        if (hint == CauseRankHint.POOR_SLEEP &&
            (
                context.sleepScore != null &&
                    context.sleepScore < 60f ||
                    context.sleepDurationMinutes != null &&
                    context.goalSleepMinutes != null &&
                    context.sleepDurationMinutes < context.goalSleepMinutes * 0.85f
            )
        ) {
            score += 100
        }

        if (hint == CauseRankHint.LOW_HRV &&
            context.zLnHrv != null &&
            context.zLnHrv <= -1.0f
        ) {
            score += 90
        }

        if (hint == CauseRankHint.VERY_LOW_HRV &&
            context.zLnHrv != null &&
            context.zLnHrv <= -1.5f
        ) {
            score += 110
        }

        if (hint == CauseRankHint.ELEVATED_RHR &&
            (
                context.zRhr != null &&
                    context.zRhr >= 1.0f ||
                    context.rhrDeltaBpm != null &&
                    context.rhrDeltaBpm >= 3f
            )
        ) {
            score += 90
        }

        if (hint == CauseRankHint.STRONG_ELEVATED_RHR &&
            (
                context.zRhr != null &&
                    context.zRhr >= 2.0f ||
                    context.rhrDeltaBpm != null &&
                    context.rhrDeltaBpm >= 5f
            )
        ) {
            score += 110
        }

        if (hint == CauseRankHint.LOW_SPO2 &&
            context.avgSleepingSpo2 != null &&
            context.avgSleepingSpo2 < 94f
        ) {
            score += if (insightId == InsightType.HRV_DROP_LOW_SPO2) 120 else 100
        }

        if (hint == CauseRankHint.LARGE_BEDTIME_SHIFT &&
            context.bedtimeOffsetMinutes != null &&
            context.bedtimeOffsetMinutes > 90
        ) {
            score += 100
        }

        if (hint == CauseRankHint.LOW_ACTIVITY &&
            context.stepCount != null &&
            context.stepGoal != null &&
            context.stepCount < context.stepGoal * 0.7f
        ) {
            score += 100
        }

        return score
    }

    private data class RankedCause(
        val cause: InsightCause,
        val score: Int,
        val index: Int,
    )
}
