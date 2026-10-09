package app.readylytics.health.core.ui.components.metriccard

import app.readylytics.health.core.model.domain.model.MetricStatus

enum class UniversalMetricUnavailableReason {
    MISSING_VALUE,
    MISSING_TARGET,
    BASELINE_NOT_READY,
    MISSING_BMI,
}

sealed interface UniversalMetricVisual {
    data class Score(
        val rawValue: Float?,
        val minValue: Float,
        val maxValue: Float,
        val markerFraction: Float?,
        val unavailableReason: UniversalMetricUnavailableReason?,
    ) : UniversalMetricVisual

    data class Goal(
        val rawValue: Float?,
        val targetValue: Float?,
        val markerFraction: Float?,
        val targetMarkerFraction: Float?,
        val isAboveTarget: Boolean,
        val selectionAvailable: Boolean,
        val unavailableReason: UniversalMetricUnavailableReason?,
    ) : UniversalMetricVisual

    data class PersonalBaseline(
        val rawValue: Float?,
        val baselineValue: Float?,
        val ratio: Float?,
        val markerFraction: Float?,
        val baselineMarkerFraction: Float,
        val selectionAvailable: Boolean,
        val unavailableReason: UniversalMetricUnavailableReason?,
    ) : UniversalMetricVisual

    data class ReferenceRange(
        val rawValue: Float?,
        val markerFraction: Float?,
        val referenceMarkerFraction: Float?,
        val selectionAvailable: Boolean,
        val unavailableReason: UniversalMetricUnavailableReason?,
    ) : UniversalMetricVisual

    data object ValueOnly : UniversalMetricVisual
}

data class UniversalMetricPresentation(
    val title: String,
    val valueText: String,
    val unitText: String,
    val secondaryText: String?,
    val status: MetricStatus,
    val tooltip: String,
    val accessibilityDescription: String,
    val visual: UniversalMetricVisual,
    /** Gauge-only override: when a combined [valueText] is too wide for the horseshoe,
     *  callers split it into a short value (top) and a unit-like suffix (bottom). */
    val gaugeValueTextOverride: String? = null,
    val gaugeUnitTextOverride: String? = null,
) {
    val gaugeValueText: String
        get() = gaugeValueTextOverride ?: valueText

    val gaugeUnitText: String
        get() = gaugeUnitTextOverride ?: unitText

    /**
     * Status that drives the card's colors. A reading whose personal baseline isn't ready yet --
     * still calibrating, or today's baseline snapshot not written yet while a sync is in flight --
     * is real data, so it is tinted like a neutral reading instead of the muted empty-card tone
     * that made it look disabled next to its siblings. [status] still drives classification,
     * tooltips and accessibility; cards without a value keep the empty-card tone.
     */
    val colorStatus: MetricStatus
        get() =
            if (status == MetricStatus.CALIBRATING && visual.hasValueAwaitingBaseline()) {
                MetricStatus.NEUTRAL
            } else {
                status
            }
}

private fun UniversalMetricVisual.hasValueAwaitingBaseline(): Boolean =
    this is UniversalMetricVisual.PersonalBaseline &&
        rawValue != null &&
        unavailableReason == UniversalMetricUnavailableReason.BASELINE_NOT_READY
