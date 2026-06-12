package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags an elevated systolic blood pressure reading, relative to the user's
 * recent baseline, occurring alongside a high acute-vs-chronic strain ratio.
 */
class BpElevatedHighStrainRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val todaySystolic = context.today.bloodPressureSystolic ?: return null

        val baselineReadings =
            context.recentDays
                .filter { it.date != context.today.date }
                .take(7)
                .mapNotNull { it.bloodPressureSystolic }
        if (baselineReadings.size < InsightConstants.MIN_BP_BASELINE_SAMPLES) return null

        val baseline = baselineReadings.sum() / baselineReadings.size.toFloat()
        val drift = todaySystolic - baseline
        if (drift <= InsightConstants.BP_SYSTOLIC_DRIFT_THRESHOLD_MMHG) return null

        val strainRatio = context.today.strainRatio ?: 0f
        if (strainRatio <= InsightConstants.STRAIN_HIGH_RATIO_THRESHOLD) return null

        return InsightFinding(
            type = InsightType.BP_ELEVATED_HIGH_STRAIN,
            params =
                InsightParams.BpElevatedStrain(
                    systolicDriftMmHg = drift.toInt(),
                    strainRatio = strainRatio,
                ),
        )
    }
}
