package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.model.circadianConsistencyStatus
import app.readylytics.health.core.model.domain.model.normalizedSleepEfficiencyPercent
import app.readylytics.health.core.model.domain.model.sleepEfficiencyStatus
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import javax.inject.Inject
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardSleepRhythmMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
    ) {
        private val helpers = DashboardMetricPresentationHelpers(resourceProvider)

        fun build(
            lastSleepSession: SleepSessionSummary? = null,
            circadianResult: CircadianConsistencyResult? = null,
            unavailableValueText: String = resourceProvider.getString(CoreUiR.string.metric_value_unavailable),
            scoreMaximumText: String = resourceProvider.getString(CoreUiR.string.score_maximum),
        ): Map<CardId, UniversalMetricPresentation> =
            mapOf(
                CardId.SLEEP_EFFICIENCY to sleepEfficiencyPresentation(lastSleepSession, unavailableValueText),
                CardId.CIRCADIAN_CONSISTENCY to
                    circadianConsistencyPresentation(circadianResult, unavailableValueText, scoreMaximumText),
            )

        private fun sleepEfficiencyPresentation(
            lastSleepSession: SleepSessionSummary?,
            unavailableValueText: String,
        ): UniversalMetricPresentation {
            val efficiency = lastSleepSession?.efficiency
            val efficiencyPercent = efficiency.normalizedSleepEfficiencyPercent()?.takeIf { it.isFinite() }
            val effStatus = efficiencyPercent.sleepEfficiencyStatus()

            val effValText =
                if (efficiencyPercent == null) {
                    unavailableValueText
                } else if (efficiencyPercent == 0f) {
                    "0%"
                } else {
                    "${efficiencyPercent.roundToInt()}%"
                }
            val effVisual =
                UniversalMetricScalePreparer.score(
                    efficiencyPercent,
                    0f,
                    100f,
                )
            val sleepEffTitle =
                resourceProvider.getString(
                    CoreUiR.string.card_title_sleep_efficiency,
                )
            val sleepEffDescription =
                effVisual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(sleepEffTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    sleepEffTitle,
                    effValText,
                    helpers.classificationText(effStatus),
                )
            return UniversalMetricPresentation(
                title = sleepEffTitle,
                valueText = effValText,
                unitText = "",
                secondaryText = null,
                status = effStatus,
                tooltip = resourceProvider.getString(CoreUiR.string.card_tooltip_sleep_efficiency),
                accessibilityDescription = sleepEffDescription,
                visual = effVisual,
            )
        }

        private fun circadianConsistencyPresentation(
            circadianResult: CircadianConsistencyResult?,
            unavailableValueText: String,
            scoreMaximumText: String,
        ): UniversalMetricPresentation {
            val circReady = circadianResult as? CircadianConsistencyResult.Ready
            val circTitle =
                resourceProvider.getString(
                    DashboardR.string.card_title_circadian_consistency,
                )
            val circSemanticsValueText = circReady?.score?.roundToInt()?.toString() ?: unavailableValueText
            val circValueText = circReady?.score?.roundToInt()?.let { "$it%" } ?: unavailableValueText
            val circVisual =
                UniversalMetricScalePreparer.score(
                    circReady?.score,
                    0f,
                    100f,
                )
            val circStatus = circReady?.score.circadianConsistencyStatus()
            val circadianDescription =
                circVisual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(circTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    circTitle,
                    circSemanticsValueText,
                    scoreMaximumText,
                    helpers.classificationText(circStatus),
                )
            return UniversalMetricPresentation(
                title = circTitle,
                valueText = circValueText,
                unitText = "",
                secondaryText = null,
                status = circStatus,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_circadian_score),
                accessibilityDescription = circadianDescription,
                visual = circVisual,
            )
        }
    }
