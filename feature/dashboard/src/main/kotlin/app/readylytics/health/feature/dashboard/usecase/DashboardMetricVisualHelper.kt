package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.feature.dashboard.DashboardMetricVisual

fun DashboardMetricVisual.getResolvedStatus(): MetricStatus {
    return when (this) {
        is DashboardMetricVisual.Score -> {
            if (markerFraction == null) return MetricStatus.CALIBRATING
            bands.firstOrNull { markerFraction >= it.startFraction && markerFraction <= it.endFraction }?.status ?: MetricStatus.NEUTRAL
        }
        is DashboardMetricVisual.Goal -> {
            if (markerFraction == null) return MetricStatus.CALIBRATING
            bands.firstOrNull { markerFraction >= it.startFraction && markerFraction <= it.endFraction }?.status ?: MetricStatus.NEUTRAL
        }
        is DashboardMetricVisual.PersonalBaseline -> {
            if (markerFraction == null) return MetricStatus.CALIBRATING
            bands.firstOrNull { markerFraction >= it.startFraction && markerFraction <= it.endFraction }?.status ?: MetricStatus.NEUTRAL
        }
        is DashboardMetricVisual.ReferenceRange -> {
            if (markerFraction == null) return MetricStatus.CALIBRATING
            bands.firstOrNull { markerFraction >= it.startFraction && markerFraction <= it.endFraction }?.status ?: MetricStatus.NEUTRAL
        }
        is DashboardMetricVisual.ValueOnly -> MetricStatus.NEUTRAL
    }
}
