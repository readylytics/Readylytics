package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

internal class DashboardMetricPresentationHelpers(
    private val resourceProvider: ResourceProvider,
) {
    fun classificationText(status: MetricStatus): String =
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

    fun unavailableReasonText(reason: UniversalMetricUnavailableReason): String =
        resourceProvider.getString(
            when (reason) {
                UniversalMetricUnavailableReason.MISSING_VALUE ->
                    CoreUiR.string.metric_unavailable_missing_value
                UniversalMetricUnavailableReason.MISSING_TARGET ->
                    CoreUiR.string.metric_unavailable_missing_target
                UniversalMetricUnavailableReason.BASELINE_NOT_READY ->
                    CoreUiR.string.metric_unavailable_baseline_not_ready
                UniversalMetricUnavailableReason.MISSING_BMI ->
                    CoreUiR.string.metric_unavailable_missing_bmi
            },
        )

    fun unavailableDescription(
        title: String,
        reason: UniversalMetricUnavailableReason,
    ): String =
        resourceProvider.getString(
            DashboardR.string.semantics_unavailable_format,
            title,
            unavailableReasonText(reason),
        )
}
