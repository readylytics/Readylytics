package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType

class LoadSpikeRecoveryStrainRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (!hasLoadSpike(context)) return null
        if (!hasRecoveryStrain(context)) return null

        return InsightFinding(
            type = InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
            params = InsightParams.None,
        )
    }

    private fun hasLoadSpike(context: InsightContext): Boolean {
        val today = context.today
        val strainRatioSpike =
            (today.strainRatio ?: 0f) > InsightConstants.LOAD_SPIKE_STRAIN_RATIO_THRESHOLD

        val yesterdayTrimp =
            context.recentDays
                .filter { it.date < today.date }
                .maxByOrNull { it.date }
                ?.totalTrimp
        val yesterdaySpike =
            (yesterdayTrimp ?: 0f) >= InsightConstants.LOAD_SPIKE_TRIMP_THRESHOLD

        val validLoadDays =
            context.recentDays
                .filter { it.date < today.date }
                .sortedByDescending { it.date }
                .mapNotNull(DailySummary::totalTrimp)
        val hasEnoughLoadHistory = validLoadDays.size >= InsightConstants.LOAD_HISTORY_MIN_VALID_DAYS
        val chronic28dLoad =
            validLoadDays
                .take(28)
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
        val acute7dLoad =
            validLoadDays
                .take(7)
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
        val acwrSpike =
            hasEnoughLoadHistory &&
                acute7dLoad != null &&
                chronic28dLoad != null &&
                chronic28dLoad > 0f &&
                acute7dLoad / chronic28dLoad >= InsightConstants.LOAD_SPIKE_ACWR_THRESHOLD

        return strainRatioSpike || yesterdaySpike || acwrSpike
    }

    private fun hasRecoveryStrain(context: InsightContext): Boolean {
        val today = context.today
        val rhrDelta = today.readinessResult.diagnostics.rhrDeltaBpm
        val shortSleep =
            today.sleepDurationMinutes != null &&
                today.sleepDurationMinutes < context.goalSleepMinutes * InsightConstants.SLEEP_DEFICIT_RATIO

        return (today.zLnHrv ?: 0f) <= InsightConstants.RECOVERY_STRAIN_LOW_HRV_Z ||
            (today.zRhr ?: 0f) >= InsightConstants.RECOVERY_STRAIN_ELEVATED_RHR_Z ||
            (rhrDelta ?: 0f) >= InsightConstants.RECOVERY_STRAIN_RHR_DELTA_BPM ||
            (today.readinessScore ?: 100f) < InsightConstants.RECOVERY_STRAIN_READINESS_THRESHOLD ||
            shortSleep
    }
}
