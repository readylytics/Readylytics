package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.strainRatioStatus
import app.readylytics.health.core.ui.R as CoreUiR

fun buildWorkoutsCardDataMap(
    uiState: WorkoutsUiState,
    isEditing: Boolean,
    onWorkoutsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
): Map<CardId, @Composable (CardConfiguration) -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable (CardConfiguration) -> Unit>()

    cardMap[CardId.STRAIN_RATIO] = { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.STRAIN_RATIO)
        if (spec != null) {
            val strainRatio = uiState.latestMetrics?.strainRatioRaw
            val strainStatus = strainRatio?.strainRatioStatus() ?: MetricStatus.CALIBRATING
            val requestedMode = DashboardCardCatalog.requestedMode(configuration)
            val strainDelta =
                if (uiState.todayStrainIncrease != null) {
                    if (uiState.todayStrainIncrease > 0.005f) {
                        val diffFormatted = MetricFormatter.formatStrain(uiState.todayStrainIncrease)
                        stringResource(
                            CoreUiR.string.delta_up_format,
                            stringResource(CoreUiR.string.delta_up),
                            diffFormatted,
                        )
                    } else {
                        stringResource(CoreUiR.string.delta_no_change)
                    }
                } else {
                    null
                }
            UniversalWorkoutMetricCard(
                title = stringResource(CardId.STRAIN_RATIO.displayNameResId),
                rawValue = strainRatio,
                maxValue = 2.0f,
                valueText =
                    uiState.latestMetrics?.strainRatioDisplay ?: stringResource(
                        CoreUiR.string.metric_value_unavailable,
                    ),
                unitText = "",
                status = strainStatus,
                secondaryText = strainDelta,
                tooltip = stringResource(CoreUiR.string.tooltip_strain_ratio),
                mode = requestedMode.toUniversalMode(),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                isEditing = isEditing,
                onModeSelected = { mode ->
                    onWorkoutsCardDisplayModeChanged(CardId.STRAIN_RATIO, mode.toDashboardMode())
                },
            )
        }
    }

    cardMap[CardId.READINESS] = { configuration ->
        val spec = DashboardCardCatalog.spec(CardId.READINESS)
        if (spec != null) {
            val readinessVal = uiState.latestMetrics?.readinessRounded?.toFloat()
            val readinessStatus =
                readinessVal?.let {
                    when {
                        it >= 85f -> MetricStatus.OPTIMAL
                        it >= 60f -> MetricStatus.NEUTRAL
                        it >= 40f -> MetricStatus.WARNING
                        else -> MetricStatus.POOR
                    }
                } ?: MetricStatus.CALIBRATING
            val requestedMode = DashboardCardCatalog.requestedMode(configuration)
            val readinessDelta =
                formatRoundedScoreDelta(
                    currentRounded = uiState.latestMetrics?.readinessRounded,
                    previousRounded = uiState.yesterdayReadiness?.toInt(),
                ).resolveOrNull()
            UniversalWorkoutMetricCard(
                title = stringResource(CardId.READINESS.displayNameResId),
                rawValue = readinessVal,
                maxValue = 100f,
                valueText =
                    uiState.latestMetrics?.readinessRounded?.toString()
                        ?: stringResource(CoreUiR.string.metric_value_unavailable),
                unitText = "",
                status = readinessStatus,
                secondaryText = readinessDelta,
                tooltip = stringResource(CoreUiR.string.tooltip_readiness),
                mode = requestedMode.toUniversalMode(),
                supportedModes = spec.supportedModes.map { it.toUniversalMode() },
                isEditing = isEditing,
                onModeSelected = { mode -> onWorkoutsCardDisplayModeChanged(CardId.READINESS, mode.toDashboardMode()) },
            )
        }
    }

    cardMap[CardId.RAS_DAILY] = { _ ->
        RasWeeklyCard(
            dailyBreakdown = uiState.rasDailyBreakdown,
            totalRas = uiState.latestMetrics?.rasRounded,
        )
    }

    return cardMap
}
