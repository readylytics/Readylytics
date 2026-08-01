package app.readylytics.health.feature.dashboard

import app.readylytics.health.domain.model.MetricStatus

enum class DashboardMetricUnavailableReason {
    MISSING_VALUE,
    MISSING_TARGET,
    BASELINE_NOT_READY,
    MISSING_BMI,
}

sealed interface DashboardMetricVisual {
    data class Score(
        val rawValue: Float?,
        val minValue: Float,
        val maxValue: Float,
        val markerFraction: Float?,
        val unavailableReason: DashboardMetricUnavailableReason?,
    ) : DashboardMetricVisual

    data class Goal(
        val rawValue: Float?,
        val targetValue: Float?,
        val markerFraction: Float?,
        val targetMarkerFraction: Float?,
        val isAboveTarget: Boolean,
        val selectionAvailable: Boolean,
        val unavailableReason: DashboardMetricUnavailableReason?,
    ) : DashboardMetricVisual

    data class PersonalBaseline(
        val rawValue: Float?,
        val baselineValue: Float?,
        val ratio: Float?,
        val markerFraction: Float?,
        val baselineMarkerFraction: Float,
        val selectionAvailable: Boolean,
        val unavailableReason: DashboardMetricUnavailableReason?,
    ) : DashboardMetricVisual

    data class ReferenceRange(
        val rawValue: Float?,
        val markerFraction: Float?,
        val referenceMarkerFraction: Float?,
        val selectionAvailable: Boolean,
        val unavailableReason: DashboardMetricUnavailableReason?,
    ) : DashboardMetricVisual

    data object ValueOnly : DashboardMetricVisual
}

data class DashboardMetricPresentation(
    val title: String,
    val valueText: String,
    val unitText: String,
    val secondaryText: String?,
    val status: MetricStatus,
    val tooltip: String,
    val accessibilityDescription: String,
    val visual: DashboardMetricVisual,
)
