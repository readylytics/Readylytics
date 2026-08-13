package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec

internal fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
    when (this) {
        DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
        DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
        DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
    }

internal fun UniversalCardDisplayMode.toDashboardMode(): DashboardCardDisplayMode =
    when (this) {
        UniversalCardDisplayMode.GAUGE -> DashboardCardDisplayMode.GAUGE
        UniversalCardDisplayMode.BAR -> DashboardCardDisplayMode.BAR
        UniversalCardDisplayMode.VALUE -> DashboardCardDisplayMode.VALUE
    }

internal fun DashboardCardSpec.toUniversalSpec(): UniversalMetricCardSpec =
    UniversalMetricCardSpec(
        supportedModes = supportedModes.map { it.toUniversalMode() },
        usesDeltaPill = cardId.usesDeltaPill(),
    )

// From the deleted DashboardMetricRenderers.kt
internal fun CardId.usesDeltaPill(): Boolean =
    when (this) {
        CardId.SLEEP_SCORE,
        CardId.READINESS,
        CardId.HRV,
        CardId.SLEEP_RHR,
        CardId.RESTING_HR,
        CardId.STRAIN_RATIO,
        CardId.RAS_DAILY,
        CardId.BODY_TEMPERATURE,
        -> true
        else -> false
    }
