package app.readylytics.health.core.scoring.domain.insights.detail

import app.readylytics.health.core.model.domain.model.InsightType

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
        if (hint == CauseRankHint.LATE_WORKOUT && isLateWorkout(context)) score += 100
        if (hint == CauseRankHint.HIGH_STRAIN_RATIO && isHighStrainRatio(context)) score += 100
        if (hint == CauseRankHint.HIGH_TRIMP_YESTERDAY && isHighTrimpYesterday(context)) score += 100
        if (hint == CauseRankHint.POOR_SLEEP && isPoorSleep(context)) score += 100
        if (hint == CauseRankHint.LOW_HRV && isLowHrv(context)) score += 90
        if (hint == CauseRankHint.VERY_LOW_HRV && isVeryLowHrv(context)) score += 110
        if (hint == CauseRankHint.ELEVATED_RHR && isElevatedRhr(context)) score += 90
        if (hint == CauseRankHint.STRONG_ELEVATED_RHR && isStrongElevatedRhr(context)) score += 110
        if (hint == CauseRankHint.LOW_SPO2 && isLowSpo2(context)) {
            score += if (insightId == InsightType.HRV_DROP_LOW_SPO2) 120 else 100
        }
        if (hint == CauseRankHint.LARGE_BEDTIME_SHIFT && isLargeBedtimeShift(context)) score += 100
        if (hint == CauseRankHint.LOW_ACTIVITY && isLowActivity(context)) score += 100
        return score
    }

    private fun isLateWorkout(context: DailyInsightContext) =
        context.lastWorkoutEndedMinutesBeforeSleep != null &&
            context.lastWorkoutEndedMinutesBeforeSleep <= 240

    private fun isHighStrainRatio(context: DailyInsightContext) =
        context.strainRatio != null && context.strainRatio > 1.3f

    private fun isHighTrimpYesterday(context: DailyInsightContext) =
        context.yesterdayTrimp != null && context.yesterdayTrimp >= 120f

    private fun isPoorSleep(context: DailyInsightContext) =
        context.sleepScore != null && context.sleepScore < 60f ||
            context.sleepDurationMinutes != null &&
            context.goalSleepMinutes != null &&
            context.sleepDurationMinutes < context.goalSleepMinutes * 0.85f

    private fun isLowHrv(context: DailyInsightContext) =
        context.zLnHrv != null && context.zLnHrv <= -1.0f

    private fun isVeryLowHrv(context: DailyInsightContext) =
        context.zLnHrv != null && context.zLnHrv <= -1.5f

    private fun isElevatedRhr(context: DailyInsightContext) =
        context.zRhr != null && context.zRhr >= 1.0f ||
            context.rhrDeltaBpm != null && context.rhrDeltaBpm >= 3f

    private fun isStrongElevatedRhr(context: DailyInsightContext) =
        context.zRhr != null && context.zRhr >= 2.0f ||
            context.rhrDeltaBpm != null && context.rhrDeltaBpm >= 5f

    private fun isLowSpo2(context: DailyInsightContext) =
        context.avgSleepingSpo2 != null && context.avgSleepingSpo2 < 94f

    private fun isLargeBedtimeShift(context: DailyInsightContext) =
        context.bedtimeOffsetMinutes != null && context.bedtimeOffsetMinutes > 90

    private fun isLowActivity(context: DailyInsightContext) =
        context.stepCount != null &&
            context.stepGoal != null &&
            context.stepCount < context.stepGoal * 0.7f

    private data class RankedCause(
        val cause: InsightCause,
        val score: Int,
        val index: Int,
    )
}
