package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.scoring.LoadSourceMode

class LoadSpikeRecoveryStrainRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (!hasLoadSpike(context)) return null
        if (!hasRecoveryStrain(context)) return null

        return InsightFinding(
            type = InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
            params =
                InsightParams.LoadSpikeRecoveryStrain(
                    everydayMode = context.prefs.strainLoadSourceMode == LoadSourceMode.EVERYDAY_HEART_RATE,
                ),
        )
    }

    private fun hasLoadSpike(context: InsightContext): Boolean {
        val today = context.today
        val mode = context.prefs.strainLoadSourceMode
        val strainRatioSpike =
            (LoadSourceSelector.selectStrainRatio(today, mode) ?: 0f) >
                InsightConstants.LOAD_SPIKE_STRAIN_RATIO_THRESHOLD

        val yesterdayTrimp =
            context.recentDays
                .filter { it.date < today.date }
                .maxByOrNull { it.date }
                ?.let { LoadSourceSelector.selectTrimp(it, mode) }
        val yesterdaySpike =
            (yesterdayTrimp ?: 0f) >= InsightConstants.LOAD_SPIKE_TRIMP_THRESHOLD

        val validLoadDays =
            context.recentDays
                .filter { it.date < today.date }
                .sortedByDescending { it.date }
                .mapNotNull { LoadSourceSelector.selectTrimp(it, mode) }
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
        val readiness = LoadSourceSelector.selectReadiness(today, context.prefs.strainLoadSourceMode)

        return (today.zLnHrv ?: 0f) <= InsightConstants.RECOVERY_STRAIN_LOW_HRV_Z ||
            (today.zRhr ?: 0f) >= InsightConstants.RECOVERY_STRAIN_ELEVATED_RHR_Z ||
            (rhrDelta ?: 0f) >= InsightConstants.RECOVERY_STRAIN_RHR_DELTA_BPM ||
            (readiness ?: 100f) < InsightConstants.RECOVERY_STRAIN_READINESS_THRESHOLD ||
            shortSleep
    }
}
