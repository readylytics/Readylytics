package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags a sharp overnight HRV drop combined with low blood oxygen, which can
 * indicate the onset of illness or a breathing disruption such as sleep apnea.
 */
class HrvDropLowSpo2Rule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val zLnHrv = context.today.zLnHrv ?: return null
        if (zLnHrv >= InsightConstants.HRV_DROP_ZSCORE_THRESHOLD) return null

        val spo2 = context.today.avgSleepingSpo2 ?: return null
        if (spo2 >= InsightConstants.SPO2_HYPOXIA_THRESHOLD) return null

        return InsightFinding(
            type = InsightType.HRV_DROP_LOW_SPO2,
            params = InsightParams.HrvSpo2(zLnHrv = zLnHrv, spo2 = spo2),
        )
    }
}
