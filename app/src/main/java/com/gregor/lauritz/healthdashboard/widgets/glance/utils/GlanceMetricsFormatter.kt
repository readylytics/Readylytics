package com.gregor.lauritz.healthdashboard.widgets.glance.utils

import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlin.math.roundToInt

/**
 * Formats metric values for display in widgets.
 * Provides value, unit, and trend formatting.
 */
object GlanceMetricsFormatter {
    /**
     * Format a metric value based on its type.
     */
    fun formatValue(
        type: MetricType,
        value: Double,
    ): String =
        when (type) {
            MetricType.HRV -> "${value.roundToInt()}"
            MetricType.RHR -> "${value.roundToInt()}"
            MetricType.SLEEP_SCORE -> "${value.roundToInt()}"
            MetricType.SLEEP_DURATION -> formatDuration(value.toLong())
            MetricType.SLEEP_EFFICIENCY -> "${value.roundToInt()}%"
            MetricType.RECOVERY -> "${value.roundToInt()}%"
            MetricType.READINESS -> "${value.roundToInt()}"
            MetricType.STRESS -> "${value.roundToInt()}"
            MetricType.BODY_BATTERY -> "${value.roundToInt()}%"
            MetricType.STEPS -> formatSteps(value.toLong())
            MetricType.PAI -> "${value.roundToInt()}"
            MetricType.STRAIN_RATIO -> "%.2f".format(value)
            MetricType.CIRCADIAN_CONSISTENCY -> "${value.roundToInt()}%"
            MetricType.CALORIES -> "${value.roundToInt()}"
            MetricType.VO2_MAX -> "%.1f".format(value)
            MetricType.WEIGHT -> "%.1f".format(value)
        }

    /**
     * Get unit string for metric type.
     */
    fun getUnit(type: MetricType): String =
        when (type) {
            MetricType.HRV -> "ms"
            MetricType.RHR -> "bpm"
            MetricType.SLEEP_SCORE -> ""
            MetricType.SLEEP_DURATION -> ""
            MetricType.SLEEP_EFFICIENCY -> "%"
            MetricType.RECOVERY -> "%"
            MetricType.READINESS -> ""
            MetricType.STRESS -> ""
            MetricType.BODY_BATTERY -> "%"
            MetricType.STEPS -> ""
            MetricType.PAI -> ""
            MetricType.STRAIN_RATIO -> ""
            MetricType.CIRCADIAN_CONSISTENCY -> "%"
            MetricType.CALORIES -> "kcal"
            MetricType.VO2_MAX -> "ml/kg/min"
            MetricType.WEIGHT -> "kg"
        }

    /**
     * Format trend as arrow symbol.
     */
    fun formatTrend(trend: Double?): String? =
        when {
            trend == null -> null
            trend > 5 -> "↑"
            trend < -5 -> "↓"
            else -> "→"
        }

    /**
     * Format duration in minutes to human-readable format.
     */
    private fun formatDuration(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) {
            "${hours}h ${mins}m"
        } else {
            "${mins}m"
        }
    }

    /**
     * Format step count with thousand separators.
     */
    private fun formatSteps(steps: Long): String =
        when {
            steps >= 1_000_000 -> "%.1fM".format(steps / 1_000_000.0)
            steps >= 1_000 -> "%.1fk".format(steps / 1_000.0)
            else -> steps.toString()
        }

    /**
     * Get short label for metric type (for compact display).
     */
    fun getShortLabel(type: MetricType): String =
        when (type) {
            MetricType.HRV -> "HRV"
            MetricType.RHR -> "RHR"
            MetricType.SLEEP_SCORE -> "Sleep"
            MetricType.SLEEP_DURATION -> "Duration"
            MetricType.SLEEP_EFFICIENCY -> "Efficiency"
            MetricType.RECOVERY -> "Recovery"
            MetricType.READINESS -> "Readiness"
            MetricType.STRESS -> "Stress"
            MetricType.BODY_BATTERY -> "Battery"
            MetricType.STEPS -> "Steps"
            MetricType.PAI -> "PAI"
            MetricType.STRAIN_RATIO -> "Strain"
            MetricType.CIRCADIAN_CONSISTENCY -> "Circadian"
            MetricType.CALORIES -> "Calories"
            MetricType.VO2_MAX -> "VO2 Max"
            MetricType.WEIGHT -> "Weight"
        }
}
