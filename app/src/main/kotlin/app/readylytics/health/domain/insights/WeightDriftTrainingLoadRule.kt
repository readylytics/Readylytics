package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import kotlin.math.abs

/**
 * Flags a meaningful body weight change since the start of the rolling
 * window occurring alongside a high acute-vs-chronic strain ratio, which can
 * indicate fluid shifts or under-fueling relative to training load.
 */
class WeightDriftTrainingLoadRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val todayWeight = context.today.weightKg ?: return null

        val days =
            (listOf(context.today) + context.recentDays)
                .distinctBy { it.date }
                .sortedByDescending { it.date }
                .take(7)
        val oldestWeight =
            days
                .filter { it.date != context.today.date }
                .lastOrNull { it.weightKg != null }
                ?.weightKg
                ?: return null

        val deltaKg = todayWeight - oldestWeight
        val percent = abs(deltaKg) / oldestWeight
        if (percent <= InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD) return null

        val strainRatio = context.today.strainRatio ?: 0f
        if (strainRatio <= InsightConstants.STRAIN_HIGH_RATIO_THRESHOLD) return null

        return InsightFinding(
            type = InsightType.WEIGHT_DRIFT_TRAINING_LOAD,
            params = InsightParams.WeightDrift(deltaKg = deltaKg, percent = percent),
        )
    }
}
