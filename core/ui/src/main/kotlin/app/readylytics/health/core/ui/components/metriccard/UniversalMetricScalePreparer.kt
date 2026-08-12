package app.readylytics.health.core.ui.components.metriccard

import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual

object UniversalMetricScalePreparer {
    private fun linearFraction(
        value: Float,
        minimum: Float,
        maximum: Float,
    ): Float {
        require(minimum < maximum) { "minimum ($minimum) must be less than maximum ($maximum)" }
        return ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
    }

    fun piecewiseFraction(
        value: Float,
        minimum: Float,
        midpoint: Float,
        maximum: Float,
    ): Float {
        require(minimum < midpoint) { "minimum ($minimum) must be less than midpoint ($midpoint)" }
        require(midpoint < maximum) { "midpoint ($midpoint) must be less than maximum ($maximum)" }
        val clamped = value.coerceIn(minimum, maximum)
        return if (clamped <= midpoint) {
            0.5f * (clamped - minimum) / (midpoint - minimum)
        } else {
            0.5f + 0.5f * (clamped - midpoint) / (maximum - midpoint)
        }
    }

    fun score(
        value: Float?,
        minimum: Float,
        maximum: Float,
    ): UniversalMetricVisual.Score {
        val markerFraction = if (value != null) linearFraction(value, minimum, maximum) else null

        return UniversalMetricVisual.Score(
            rawValue = value,
            minValue = minimum,
            maxValue = maximum,
            markerFraction = markerFraction,
            unavailableReason = if (value == null) UniversalMetricUnavailableReason.MISSING_VALUE else null,
        )
    }

    fun goal(
        value: Float?,
        target: Float?,
    ): UniversalMetricVisual.Goal {
        val isValidTarget = target != null && target > 0f

        val markerFraction =
            if (isValidTarget && value != null) {
                linearFraction(value, 0f, target)
            } else {
                null
            }

        val unavailableReason =
            when {
                !isValidTarget -> UniversalMetricUnavailableReason.MISSING_TARGET
                value == null -> UniversalMetricUnavailableReason.MISSING_VALUE
                else -> null
            }

        return UniversalMetricVisual.Goal(
            rawValue = value,
            targetValue = target,
            markerFraction = markerFraction,
            targetMarkerFraction = if (isValidTarget) 1f else null,
            isAboveTarget = if (isValidTarget && value != null) value > target else false,
            selectionAvailable = isValidTarget,
            unavailableReason = unavailableReason,
        )
    }

    fun personalBaseline(
        value: Float?,
        baseline: Float?,
        axisMinimumRatio: Float,
        axisMaximumRatio: Float,
        baselineReady: Boolean,
    ): UniversalMetricVisual.PersonalBaseline {
        val isValidBaseline = baseline != null && baseline > 0f && baselineReady

        val minVal = if (isValidBaseline) baseline * axisMinimumRatio else 0f
        val maxVal = if (isValidBaseline) baseline * axisMaximumRatio else 1f

        val markerFraction =
            if (isValidBaseline && value != null) {
                linearFraction(value, minVal, maxVal)
            } else {
                null
            }

        val baselineMarkerFraction =
            if (isValidBaseline) {
                linearFraction(baseline, minVal, maxVal)
            } else {
                0f
            }

        val unavailableReason =
            when {
                !isValidBaseline -> UniversalMetricUnavailableReason.BASELINE_NOT_READY
                value == null -> UniversalMetricUnavailableReason.MISSING_VALUE
                else -> null
            }

        return UniversalMetricVisual.PersonalBaseline(
            rawValue = value,
            baselineValue = baseline,
            ratio = if (isValidBaseline && value != null) value / baseline else null,
            markerFraction = markerFraction,
            baselineMarkerFraction = baselineMarkerFraction,
            selectionAvailable = isValidBaseline,
            unavailableReason = unavailableReason,
        )
    }

    fun referenceRange(
        value: Float?,
        minimum: Float,
        midpoint: Float,
        maximum: Float,
        scaleAvailable: Boolean,
        unavailableReason: UniversalMetricUnavailableReason?,
    ): UniversalMetricVisual.ReferenceRange {
        require(scaleAvailable || unavailableReason != null) {
            "An unavailable scale must provide an unavailableReason"
        }

        val markerFraction =
            if (scaleAvailable && value != null) {
                piecewiseFraction(value, minimum, midpoint, maximum)
            } else {
                null
            }

        val referenceMarkerFraction =
            if (scaleAvailable) {
                piecewiseFraction(midpoint, minimum, midpoint, maximum)
            } else {
                null
            }

        val finalUnavailableReason =
            if (scaleAvailable && value == null) {
                UniversalMetricUnavailableReason.MISSING_VALUE
            } else {
                unavailableReason
            }

        return UniversalMetricVisual.ReferenceRange(
            rawValue = value,
            markerFraction = markerFraction,
            referenceMarkerFraction = referenceMarkerFraction,
            selectionAvailable = scaleAvailable,
            unavailableReason = finalUnavailableReason,
        )
    }
}
