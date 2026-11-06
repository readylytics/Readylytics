package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.scoreStatus
import app.readylytics.health.core.model.domain.model.strainRatioStatus
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardScoreMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
    ) {
        private val helpers = DashboardMetricPresentationHelpers(resourceProvider)

        fun build(
            summary: DailySummary?,
            metrics: DailyMetrics?,
            preferences: UserPreferences,
            unavailableValueText: String,
            scoreMaximumText: String,
            todayStrainIncrease: Float? = null,
        ): Map<CardId, UniversalMetricPresentation> =
            mapOf(
                CardId.SLEEP_SCORE to
                    sleepScorePresentation(summary, metrics, unavailableValueText, scoreMaximumText),
                CardId.READINESS to
                    readinessPresentation(summary, metrics, preferences, unavailableValueText, scoreMaximumText),
                CardId.TRAINING_READINESS to
                    trainingReadinessPresentation(
                        summary,
                        metrics,
                        preferences,
                        unavailableValueText,
                        scoreMaximumText,
                    ),
                CardId.STRAIN_RATIO to
                    strainRatioPresentation(metrics, unavailableValueText, todayStrainIncrease),
            )

        private fun sleepScorePresentation(
            summary: DailySummary?,
            metrics: DailyMetrics?,
            unavailableValueText: String,
            scoreMaximumText: String,
        ): UniversalMetricPresentation {
            val visual = UniversalMetricScalePreparer.score(summary?.sleepScore, 0f, 100f)
            val status = summary?.sleepScore.scoreStatus()
            val title = resourceProvider.getString(DashboardR.string.card_title_sleep_score)
            val valueText = metrics?.sleepScoreRounded?.toString() ?: unavailableValueText
            val description =
                visual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    title,
                    valueText,
                    scoreMaximumText,
                    helpers.classificationText(status),
                )
            return UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = "",
                secondaryText = null,
                status = status,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_sleep_score),
                accessibilityDescription = description,
                visual = visual,
            )
        }

        private fun readinessPresentation(
            summary: DailySummary?,
            metrics: DailyMetrics?,
            preferences: UserPreferences,
            unavailableValueText: String,
            scoreMaximumText: String,
        ): UniversalMetricPresentation {
            val readinessScore =
                summary?.let {
                    LoadSourceSelector.selectReadiness(it, preferences.strainLoadSourceMode)
                }
            val visual = UniversalMetricScalePreparer.score(readinessScore, 0f, 100f)
            val status = readinessScore.scoreStatus()
            val title = resourceProvider.getString(CoreUiR.string.card_title_readiness)
            val valueText = metrics?.readinessRounded?.toString() ?: unavailableValueText
            val description =
                visual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    title,
                    valueText,
                    scoreMaximumText,
                    helpers.classificationText(status),
                )
            return UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = "",
                secondaryText = null,
                status = status,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_readiness),
                accessibilityDescription = description,
                visual = visual,
            )
        }

        private fun trainingReadinessPresentation(
            summary: DailySummary?,
            metrics: DailyMetrics?,
            preferences: UserPreferences,
            unavailableValueText: String,
            scoreMaximumText: String,
        ): UniversalMetricPresentation {
            val trainingReadinessScore =
                summary?.let {
                    LoadSourceSelector
                        .selectTrainingReadiness(it, preferences.strainLoadSourceMode)
                        ?.coerceIn(0f, 100f)
                }
            val visual = UniversalMetricScalePreparer.score(trainingReadinessScore, 0f, 100f)
            val status = trainingReadinessScore.scoreStatus()
            val title = resourceProvider.getString(CoreUiR.string.card_title_training_readiness)
            val valueText = metrics?.trainingReadinessRounded?.toString() ?: unavailableValueText
            val description =
                visual.unavailableReason?.let { reason ->
                    helpers.unavailableDescription(title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    title,
                    valueText,
                    scoreMaximumText,
                    helpers.classificationText(status),
                )
            return UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = "",
                secondaryText = null,
                status = status,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_training_readiness),
                accessibilityDescription = description,
                visual = visual,
            )
        }

        private fun strainRatioPresentation(
            metrics: DailyMetrics?,
            unavailableValueText: String,
            todayStrainIncrease: Float?,
        ): UniversalMetricPresentation {
            val title = resourceProvider.getString(CoreUiR.string.card_title_strain_ratio)
            val valueText = metrics?.strainRatioDisplay ?: unavailableValueText
            val strainIncreaseText =
                todayStrainIncrease?.let { increase ->
                    if (increase > 0.005f) {
                        resourceProvider.getString(
                            CoreUiR.string.delta_up_format,
                            resourceProvider.getString(CoreUiR.string.delta_up),
                            MetricFormatter.formatStrain(increase),
                        )
                    } else {
                        resourceProvider.getString(CoreUiR.string.delta_no_change)
                    }
                }
            val visual = UniversalMetricScalePreparer.score(metrics?.strainRatioRaw, 0f, 2f)
            val status = metrics?.strainRatioRaw?.strainRatioStatus() ?: MetricStatus.CALIBRATING
            val description =
                strainVisualDescription(title, visual, valueText, status)
            return UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = "",
                secondaryText = strainIncreaseText,
                status = status,
                tooltip = resourceProvider.getString(CoreUiR.string.tooltip_strain_ratio),
                accessibilityDescription = description,
                visual = visual,
            )
        }

        private fun strainVisualDescription(
            title: String,
            visual: UniversalMetricVisual.Score,
            valueText: String,
            status: MetricStatus,
        ): String =
            visual.unavailableReason?.let { reason ->
                helpers.unavailableDescription(title, reason)
            } ?: resourceProvider.getString(
                DashboardR.string.semantics_value_note_format,
                title,
                valueText,
                helpers.classificationText(status),
            )
    }
