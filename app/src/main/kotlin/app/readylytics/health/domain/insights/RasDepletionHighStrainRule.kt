package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.LoadSourceSelector

/**
 * Flags a depleted rolling RAS total occurring alongside a high
 * acute-vs-chronic strain ratio, indicating training load isn't translating
 * into RAS gains.
 */
class RasDepletionHighStrainRule : InsightRule {
    override fun evaluate(context: InsightContext): InsightFinding? {
        val totalRas = LoadSourceSelector.selectTotalRas(context.today, context.prefs.rasSourceMode) ?: return null
        if (totalRas >= InsightConstants.RAS_DEPLETION_THRESHOLD) return null

        val strainRatio =
            LoadSourceSelector.selectStrainRatio(context.today, context.prefs.strainLoadSourceMode) ?: 0f
        if (strainRatio <= InsightConstants.RAS_DEPLETION_STRAIN_RATIO_THRESHOLD) return null

        return InsightFinding(
            type = InsightType.RAS_DEPLETION_HIGH_STRAIN,
            params = InsightParams.RasDepletionStrain(totalRas = totalRas, strainRatio = strainRatio),
        )
    }
}
