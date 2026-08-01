package app.readylytics.health.feature.dashboard

object DashboardMetricScalePreparer {
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
    ): DashboardMetricVisual.Score {
        val markerFraction = if (value != null) linearFraction(value, minimum, maximum) else null

        return DashboardMetricVisual.Score(
            rawValue = value,
            minValue = minimum,
            maxValue = maximum,
            markerFraction = markerFraction,
            unavailableReason = if (value == null) DashboardMetricUnavailableReason.MISSING_VALUE else null,
        )
    }

    fun goal(
        value: Float?,
        target: Float?,
    ): DashboardMetricVisual.Goal {
        val isValidTarget = target != null && target > 0f

        val markerFraction =
            if (isValidTarget && value != null) {
                linearFraction(value, 0f, target!!)
            } else {
                null
            }

        val unavailableReason =
            when {
                !isValidTarget -> DashboardMetricUnavailableReason.MISSING_TARGET
                value == null -> DashboardMetricUnavailableReason.MISSING_VALUE
                else -> null
            }

        return DashboardMetricVisual.Goal(
            rawValue = value,
            targetValue = target,
            markerFraction = markerFraction,
            targetMarkerFraction = if (isValidTarget) 1f else null,
            isAboveTarget = if (isValidTarget && value != null) value > target!! else false,
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
    ): DashboardMetricVisual.PersonalBaseline {
        val isValidBaseline = baseline != null && baseline > 0f && baselineReady

        val minVal = if (isValidBaseline) baseline!! * axisMinimumRatio else 0f
        val maxVal = if (isValidBaseline) baseline!! * axisMaximumRatio else 1f

        val markerFraction =
            if (isValidBaseline && value != null) {
                linearFraction(value, minVal, maxVal)
            } else {
                null
            }

        val baselineMarkerFraction =
            if (isValidBaseline) {
                linearFraction(baseline!!, minVal, maxVal)
            } else {
                0f
            }

        val unavailableReason =
            when {
                !isValidBaseline -> DashboardMetricUnavailableReason.BASELINE_NOT_READY
                value == null -> DashboardMetricUnavailableReason.MISSING_VALUE
                else -> null
            }

        return DashboardMetricVisual.PersonalBaseline(
            rawValue = value,
            baselineValue = baseline,
            ratio = if (isValidBaseline && value != null) value / baseline!! else null,
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
        unavailableReason: DashboardMetricUnavailableReason?,
    ): DashboardMetricVisual.ReferenceRange {
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
                DashboardMetricUnavailableReason.MISSING_VALUE
            } else {
                unavailableReason
            }

        return DashboardMetricVisual.ReferenceRange(
            rawValue = value,
            markerFraction = markerFraction,
            referenceMarkerFraction = referenceMarkerFraction,
            selectionAvailable = scaleAvailable,
            unavailableReason = finalUnavailableReason,
        )
    }
}
