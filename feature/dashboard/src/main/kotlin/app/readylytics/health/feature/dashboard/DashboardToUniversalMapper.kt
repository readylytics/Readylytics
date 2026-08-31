package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.ModeSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode

internal fun ModeSpec.toUniversalSpec(usesDeltaPill: Boolean): UniversalMetricCardSpec =
    UniversalMetricCardSpec(
        supportedModes = supportedModes.map { it.toUniversalMode() },
        usesDeltaPill = usesDeltaPill,
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
