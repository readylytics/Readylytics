package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Flags a depleted PAI score occurring alongside a high acute-vs-chronic
 * strain ratio, indicating training load isn't translating into PAI gains.
 */
class PaiDepletionHighStrainRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val paiScore = context.today.paiScore ?: return null
        if (paiScore >= InsightConstants.PAI_DEPLETION_THRESHOLD) return null

        val strainRatio = context.today.strainRatio ?: 0f
        if (strainRatio <= InsightConstants.PAI_DEPLETION_STRAIN_RATIO_THRESHOLD) return null

        return InsightFinding(
            type = InsightType.PAI_DEPLETION_HIGH_STRAIN,
            params = InsightParams.PaiDepletionStrain(paiScore = paiScore, strainRatio = strainRatio),
        )
    }
}
