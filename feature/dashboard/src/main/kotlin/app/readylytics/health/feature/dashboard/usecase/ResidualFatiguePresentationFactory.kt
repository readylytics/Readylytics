package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import javax.inject.Inject
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

// Residual-fatigue cut-points, expressed at gain 1.0. They are multiplied by the user's configured
// fatigue gain before use so the classification tracks the scale the metric is actually produced on.
private const val RESIDUAL_FATIGUE_OPTIMAL_BELOW = 30f
private const val RESIDUAL_FATIGUE_NEUTRAL_THROUGH = 70f
private const val RESIDUAL_FATIGUE_GAUGE_MAX = 100f

/**
 * Builds the Residual Fatigue card presentation. Split out of [DashboardMetricPresentationFactory]
 * (which had grown past the LargeClass threshold) along the same seam as
 * [DashboardRecoveryMetricPresentationFactory]: this card owns a gain-scaled classification scale
 * and a live/persisted value-source rule that nothing else on the dashboard shares.
 */
class ResidualFatiguePresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
    ) {
        fun build(
            summary: DailySummary?,
            preferences: UserPreferences,
            unavailableValueText: String,
            liveResidualFatigue: LiveResidualFatigue,
        ): UniversalMetricPresentation {
            val title = resourceProvider.getString(DashboardR.string.card_residual_fatigue_title)
            val tooltip = resourceProvider.getString(DashboardR.string.tooltip_residual_fatigue)
            val value = resolveValue(summary, liveResidualFatigue)

            // Residual fatigue is `gain * sum(TRIMP) * decay`, and gain is user-settable over
            // 0.1..5.0. Fixed 30/70/100 cut-points would read OPTIMAL with a pinned-to-zero gauge
            // at the low end of that range and WARNING with a saturated gauge at the high end, so
            // the whole scale moves with the configured gain.
            val gain =
                ResidualFatigueConfig
                    .clamped(
                        halfLifeHours = preferences.residualFatigueHalfLifeHours,
                        fatigueGain = preferences.residualFatigueGain,
                    ).fatigueGain
            val gaugeMax = RESIDUAL_FATIGUE_GAUGE_MAX * gain

            val status =
                when {
                    value == null -> MetricStatus.NO_DATA
                    value < RESIDUAL_FATIGUE_OPTIMAL_BELOW * gain -> MetricStatus.OPTIMAL
                    value <= RESIDUAL_FATIGUE_NEUTRAL_THROUGH * gain -> MetricStatus.NEUTRAL
                    else -> MetricStatus.WARNING
                }

            val valueText = value?.let { MetricFormatter.formatDecimal(it, 1) } ?: unavailableValueText

            return UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = "",
                secondaryText =
                    resourceProvider.getString(
                        DashboardR.string.card_residual_fatigue_secondary,
                        preferences.residualFatigueHalfLifeHours.roundToInt(),
                    ),
                status = status,
                tooltip = tooltip,
                accessibilityDescription = accessibilityDescription(title, valueText, status, value != null),
                visual =
                    UniversalMetricVisual.Score(
                        rawValue = value,
                        minValue = 0f,
                        maxValue = gaugeMax,
                        markerFraction = value?.let { (it / gaugeMax).coerceIn(0f, 1f) },
                        unavailableReason =
                            if (value == null) UniversalMetricUnavailableReason.MISSING_VALUE else null,
                    ),
            )
        }

        /**
         * [LiveResidualFatigue.Unavailable] must NOT fall through to the persisted snapshot. The two
         * have different never-backfilled gates: the live gate blocks on any retained workout ending
         * before now, the snapshot's only on workouts starting before today. A workout logged today
         * with no backfilled TRIMP therefore blocks the live value while contributing zero to the
         * snapshot, so falling back would display a silently understated reading in exactly the case
         * the gate exists to catch.
         */
        private fun resolveValue(
            summary: DailySummary?,
            liveResidualFatigue: LiveResidualFatigue,
        ): Float? =
            when (liveResidualFatigue) {
                is LiveResidualFatigue.Value -> liveResidualFatigue.fatigue
                LiveResidualFatigue.Unavailable -> null
                LiveResidualFatigue.NotApplicable -> summary?.residualFatigue
            }

        private fun accessibilityDescription(
            title: String,
            valueText: String,
            status: MetricStatus,
            hasValue: Boolean,
        ): String =
            if (hasValue) {
                resourceProvider.getString(
                    DashboardR.string.semantics_card_residual_fatigue,
                    valueText,
                    classificationText(status),
                )
            } else {
                resourceProvider.getString(
                    DashboardR.string.semantics_unavailable_format,
                    title,
                    resourceProvider.getString(CoreUiR.string.metric_unavailable_missing_value),
                )
            }

        private fun classificationText(status: MetricStatus): String =
            resourceProvider.getString(
                when (status) {
                    MetricStatus.OPTIMAL -> CoreUiR.string.metric_status_optimal
                    MetricStatus.NEUTRAL -> CoreUiR.string.metric_status_neutral
                    MetricStatus.WARNING -> CoreUiR.string.metric_status_warning
                    MetricStatus.POOR -> CoreUiR.string.metric_status_poor
                    MetricStatus.NO_DATA,
                    MetricStatus.CALIBRATING,
                    -> CoreUiR.string.metric_status_calibrating
                },
            )
    }
