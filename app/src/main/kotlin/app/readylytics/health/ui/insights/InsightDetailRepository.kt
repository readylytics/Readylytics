package app.readylytics.health.ui.insights

import android.content.res.Resources
import app.readylytics.health.R
import app.readylytics.health.domain.insights.InsightParams
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
        params: InsightParams = InsightParams.None,
    ): InsightDetailContent {
        val spec = requireNotNull(InsightDetailResourceSpec.forType(id)) { "Missing insight detail for $id" }
        val causes = resources.getStringArray(spec.causesArrayRes).map(::parseCause)
        val cardDescriptionRes =
            if (id == InsightType.LOAD_SPIKE_RECOVERY_STRAIN &&
                params is InsightParams.LoadSpikeRecoveryStrain &&
                params.everydayMode
            ) {
                R.string.insight_load_spike_recovery_strain_body_everyday
            } else {
                spec.cardDescriptionRes
            }
        val cardDescription = getFormattedDescription(cardDescriptionRes, params)
        return InsightDetailContent(
            id = id,
            type = spec.type,
            title = resources.getString(spec.titleRes),
            cardDescription = cardDescription,
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

    private fun getFormattedDescription(
        resId: Int,
        params: InsightParams,
    ): String {
        val typeName = resources.getResourceTypeName(resId)
        return if (typeName == "plurals") {
            when (params) {
                is InsightParams.HighStrainSleepDeficit ->
                    resources.getQuantityString(
                        resId,
                        params.sleepDeficitMinutes,
                        params.strainRatio,
                        params.sleepDeficitMinutes,
                    )
                is InsightParams.LateNadirShortSleep -> {
                    val deficit = params.goalSleepMinutes - params.sleepDurationMinutes
                    resources.getQuantityString(resId, deficit, deficit, params.goalSleepMinutes)
                }
                is InsightParams.HrvDeclineStreak ->
                    resources.getQuantityString(resId, params.days, params.days)
                is InsightParams.StepShortfall ->
                    resources.getQuantityString(resId, params.stepCount, params.stepCount, params.stepGoal)
                is InsightParams.CircadianShift ->
                    resources.getQuantityString(resId, params.bedtimeOffsetMinutes, params.bedtimeOffsetMinutes)
                else -> resources.getQuantityString(resId, 1)
            }
        } else {
            when (params) {
                is InsightParams.CircadianShift -> resources.getString(resId, params.bedtimeOffsetMinutes)
                is InsightParams.HighStrainSleepDeficit ->
                    resources.getString(
                        resId,
                        params.strainRatio,
                        params.sleepDeficitMinutes,
                    )
                is InsightParams.LateNadirShortSleep -> {
                    val deficit = params.goalSleepMinutes - params.sleepDurationMinutes
                    resources.getString(resId, deficit, params.goalSleepMinutes)
                }
                is InsightParams.HrvSpo2 -> resources.getString(resId, params.zLnHrv, params.spo2)
                is InsightParams.LateNadirElevatedRhr -> resources.getString(resId, params.rhrDeltaBpm)
                is InsightParams.BpElevatedStrain ->
                    resources.getString(
                        resId,
                        params.systolicDriftMmHg,
                        params.strainRatio,
                    )
                is InsightParams.RasDepletionStrain -> resources.getString(resId, params.totalRas, params.strainRatio)
                is InsightParams.HrvDeclineStreak -> resources.getString(resId, params.days)
                is InsightParams.StepShortfall -> resources.getString(resId, params.stepCount, params.stepGoal)
                is InsightParams.RasWeeklyShortfall -> resources.getString(resId, params.weeklyRas, params.target)
                is InsightParams.WeightDrift -> resources.getString(resId, params.deltaKg, params.percent)
                else -> resources.getString(resId)
            }
        }
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
