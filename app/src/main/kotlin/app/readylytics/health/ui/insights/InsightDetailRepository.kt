package app.readylytics.health.ui.insights

import android.content.res.Resources
import app.readylytics.health.domain.insights.detail.CauseRankHint
import app.readylytics.health.domain.insights.detail.DailyInsightContext
import app.readylytics.health.domain.insights.detail.InsightCause
import app.readylytics.health.domain.insights.detail.InsightCauseRanker
import app.readylytics.health.domain.insights.detail.InsightDetailContent
import app.readylytics.health.domain.model.InsightType

class InsightDetailRepository(
    private val resources: Resources,
    private val ranker: InsightCauseRanker = InsightCauseRanker(),
) {
    fun getDetail(
        id: InsightType,
        context: DailyInsightContext,
    ): InsightDetailContent {
        val spec = requireNotNull(InsightDetailResourceSpec.forType(id)) { "Missing insight detail for $id" }
        val causes = resources.getStringArray(spec.causesArrayRes).map(::parseCause)
        return InsightDetailContent(
            id = id,
            type = spec.type,
            title = resources.getString(spec.titleRes),
            cardDescription = resources.getString(spec.cardDescriptionRes),
            observedSignalTitle = resources.getString(spec.observedSignalTitleRes),
            observedSignal = resources.getString(spec.observedSignalRes),
            meaningTitle = spec.meaningTitleRes?.let(resources::getString),
            meaning = spec.meaningRes?.let(resources::getString),
            confidence = spec.confidence,
            causesTitle = resources.getString(spec.causesTitleRes),
            causes = ranker.rankCauses(id, context, causes),
            recommendationsTitle = resources.getString(spec.recommendationsTitleRes),
            recommendations = resources.getStringArray(spec.recommendationsArrayRes).toList(),
            caveatsTitle = spec.caveatsTitleRes?.let(resources::getString),
            caveats = spec.caveatsArrayRes?.let { resources.getStringArray(it).toList() }.orEmpty(),
            safetyNote = spec.safetyNoteRes?.let(resources::getString),
        )
    }

    private fun parseCause(value: String): InsightCause {
        val parts = value.split("|", limit = 3)
        val hintString = parts.getOrNull(2)
        val rankHint =
            if (hintString != null) {
                runCatching { CauseRankHint.valueOf(hintString) }.getOrElse { CauseRankHint.GENERIC }
            } else {
                CauseRankHint.GENERIC
            }
        return InsightCause(
            title = parts[0],
            description = parts.getOrElse(1) { "" },
            rankHint = rankHint,
        )
    }
}
