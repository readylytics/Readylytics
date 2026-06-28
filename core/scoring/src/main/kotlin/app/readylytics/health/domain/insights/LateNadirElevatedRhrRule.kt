package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Explains a delayed heart rate nadir by correlating it with a meaningfully
 * elevated resting heart rate relative to baseline.
 */
class LateNadirElevatedRhrRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        if (context.today.readinessResult.diagnostics.lateNadir != true) return null

        val rhrDeltaBpm = context.today.readinessResult.diagnostics.rhrDeltaBpm ?: 0f
        if (rhrDeltaBpm <= InsightConstants.RHR_ELEVATED_DELTA_BPM) return null

        return InsightFinding(
            type = InsightType.LATE_NADIR_ELEVATED_RHR,
            params = InsightParams.LateNadirElevatedRhr(rhrDeltaBpm = rhrDeltaBpm),
        )
    }
}
