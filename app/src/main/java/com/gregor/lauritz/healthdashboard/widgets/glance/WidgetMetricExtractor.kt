package com.gregor.lauritz.healthdashboard.widgets.glance

import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType

/**
 * Shared utility for extracting metric data from DailySummary.
 * Single source of truth for all metric calculations used by widgets.
 */
object WidgetMetricExtractor {
    fun extractMetricData(
        type: MetricType,
        summary: DailySummary,
    ): Pair<Double, MetricStatus> {
        return when (type) {
            MetricType.HRV -> {
                val value = summary.nocturnalHrv?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.RHR -> {
                val value = summary.nocturnalRhr?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.SLEEP_SCORE -> {
                val value = summary.sleepScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.READINESS -> {
                val value = summary.readinessScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.RECOVERY -> {
                val value = summary.readinessScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.SLEEP_DURATION -> {
                val minutes = summary.sleepDurationMinutes ?: return 0.0 to MetricStatus.CALIBRATING
                val value = minutes / 60.0
                value to MetricStatus.NEUTRAL
            }
            MetricType.SLEEP_EFFICIENCY -> {
                val efficiency = (summary.deepSleepPercent?.plus(summary.remSleepPercent ?: 0f) ?: 0f).toDouble()
                val status =
                    when {
                        efficiency > 85 -> MetricStatus.OPTIMAL
                        efficiency > 70 -> MetricStatus.NEUTRAL
                        efficiency > 50 -> MetricStatus.WARNING
                        else -> MetricStatus.CALIBRATING
                    }
                efficiency to status
            }
            MetricType.STEPS -> {
                val value = summary.stepCount?.toDouble() ?: return 0.0 to MetricStatus.NEUTRAL
                value to MetricStatus.NEUTRAL
            }
            MetricType.PAI -> {
                val value = summary.paiScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status =
                    when {
                        value >= 100 -> MetricStatus.OPTIMAL
                        value >= 75 -> MetricStatus.NEUTRAL
                        value >= 50 -> MetricStatus.WARNING
                        else -> MetricStatus.POOR
                    }
                value to status
            }
            MetricType.STRAIN_RATIO -> {
                val value = summary.strainRatio ?: return 0.0 to MetricStatus.CALIBRATING
                val status =
                    when {
                        value in 0.8f..1.3f -> MetricStatus.OPTIMAL
                        value < 0.8 -> MetricStatus.WARNING
                        else -> MetricStatus.WARNING
                    }
                value.toDouble() to status
            }
            MetricType.BODY_BATTERY -> {
                val value = summary.readinessScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                value to if (value > 50) MetricStatus.OPTIMAL else MetricStatus.WARNING
            }
            MetricType.STRESS -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.CIRCADIAN_CONSISTENCY -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.VO2_MAX -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.WEIGHT -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.CALORIES -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
        }
    }
}
