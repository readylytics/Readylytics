package app.readylytics.health.core.ui.components.metriccard

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

/** Map the domain display mode to the UI display mode. */
fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
    when (this) {
        DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
        DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
        DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
    }

/** Map the UI display mode back to the domain display mode. */
fun UniversalCardDisplayMode.toDashboardMode(): DashboardCardDisplayMode =
    when (this) {
        UniversalCardDisplayMode.GAUGE -> DashboardCardDisplayMode.GAUGE
        UniversalCardDisplayMode.BAR -> DashboardCardDisplayMode.BAR
        UniversalCardDisplayMode.VALUE -> DashboardCardDisplayMode.VALUE
    }
