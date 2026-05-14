package com.gregor.lauritz.healthdashboard.widgets

import android.net.Uri
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes widget deep-link intents to appropriate navigation destinations.
 * Maps URI schemes like "app://metric/hrv" to navigation targets.
 */
@Singleton
class WidgetDeepLinkRouter
    @Inject
    constructor() {
        fun parseDeepLink(uri: Uri?): DeepLinkTarget? {
            uri ?: return null

            return when (uri.scheme) {
                "app" -> {
                    when (uri.host) {
                        "metric" -> {
                            val metricPath = uri.pathSegments.firstOrNull()
                            parseMetricDeepLink(metricPath)
                        }
                        "dashboard" -> DeepLinkTarget.Dashboard
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun parseMetricDeepLink(metricPath: String?): DeepLinkTarget? {
            metricPath ?: return null

            return when (metricPath.lowercase()) {
                "hrv" -> DeepLinkTarget.Metric(MetricType.HRV)
                "rhr" -> DeepLinkTarget.Metric(MetricType.RHR)
                "sleep_score" -> DeepLinkTarget.Metric(MetricType.SLEEP_SCORE)
                "sleep_duration" -> DeepLinkTarget.Metric(MetricType.SLEEP_DURATION)
                "sleep_efficiency" -> DeepLinkTarget.Metric(MetricType.SLEEP_EFFICIENCY)
                "recovery" -> DeepLinkTarget.Metric(MetricType.RECOVERY)
                "readiness" -> DeepLinkTarget.Metric(MetricType.READINESS)
                "stress" -> DeepLinkTarget.Metric(MetricType.STRESS)
                "body_battery" -> DeepLinkTarget.Metric(MetricType.BODY_BATTERY)
                "steps" -> DeepLinkTarget.Metric(MetricType.STEPS)
                "pai" -> DeepLinkTarget.Metric(MetricType.PAI)
                "strain_ratio" -> DeepLinkTarget.Metric(MetricType.STRAIN_RATIO)
                "circadian_consistency" -> DeepLinkTarget.Metric(MetricType.CIRCADIAN_CONSISTENCY)
                "calories" -> DeepLinkTarget.Metric(MetricType.CALORIES)
                "vo2_max" -> DeepLinkTarget.Metric(MetricType.VO2_MAX)
                "weight" -> DeepLinkTarget.Metric(MetricType.WEIGHT)
                else -> null
            }
        }
    }

sealed interface DeepLinkTarget {
    data object Dashboard : DeepLinkTarget

    data class Metric(
        val type: MetricType,
    ) : DeepLinkTarget
}
