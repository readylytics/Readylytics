package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags a depleted rolling PAI total occurring alongside a high
 * acute-vs-chronic strain ratio, indicating training load isn't translating
 * into PAI gains.
 */
class PaiDepletionHighStrainRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val totalPai = context.today.totalPai ?: return null
        if (totalPai >= InsightConstants.PAI_DEPLETION_THRESHOLD) return null

        val strainRatio = context.today.strainRatio ?: 0f
        if (strainRatio <= InsightConstants.PAI_DEPLETION_STRAIN_RATIO_THRESHOLD) return null

        return InsightFinding(
            type = InsightType.PAI_DEPLETION_HIGH_STRAIN,
            params = InsightParams.PaiDepletionStrain(totalPai = totalPai, strainRatio = strainRatio),
        )
    }
}
