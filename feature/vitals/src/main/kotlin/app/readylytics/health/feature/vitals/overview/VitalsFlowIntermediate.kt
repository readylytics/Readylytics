package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.CardManagementDelegate
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartManagementDelegate
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/**
 * Vitals layout state (cards + charts visibility/order/display-mode).
 * Extracted to separate testing of layout management from content/sync state.
 */
@Immutable
internal data class VitalsCardState(
    val isManagingCards: Boolean,
    val cardConfigurations: List<CardConfiguration>,
    val pendingConfiguration: List<CardConfiguration>?,
)

@Immutable
internal data class VitalsChartState(
    val isManagingCharts: Boolean,
    val chartConfigurations: List<VitalsChartConfiguration>,
    val pendingConfiguration: List<VitalsChartConfiguration>?,
)

/**
 * Creates the vitals card-state flow: merges edit-mode/pending state from the card delegate with
 * the persisted configuration, gating BODY_TEMPERATURE/OXYGEN_SATURATION on the corresponding
 * Health Connect permissions (mirrors the dashboard's createDashboardCardStateFlow).
 */
internal fun createVitalsCardStateFlow(
    cardManagementDelegate: CardManagementDelegate,
    vitalsLayoutRepository: VitalsLayoutRepository,
    healthConnectRepository: HealthConnectRepository,
): Flow<VitalsCardState> {
    // One-shot checks, not re-polled -- relies on VitalsViewModel.uiState's WhileSubscribed(5_000)
    // sharing policy naturally restarting this flow after a permission-grant round trip.
    val permissionGrants: Flow<Pair<Boolean, Boolean>> =
        combine(
            flow { emit(healthConnectRepository.hasBodyTemperaturePermission()) },
            flow { emit(healthConnectRepository.hasOxygenSaturationPermission()) },
        ) { bodyTempGranted, spo2Granted -> bodyTempGranted to spo2Granted }

    return combine(
        cardManagementDelegate.isManagingCards,
        cardManagementDelegate.pendingConfigs,
        vitalsLayoutRepository.vitalsCardConfigurations(),
        permissionGrants,
    ) { isManaging, pendingCardConfig, cardConfig, (bodyTempGranted, spo2Granted) ->
        fun List<CardConfiguration>.filteredForPermission(): List<CardConfiguration> {
            var list = this
            if (!bodyTempGranted) list = list.filter { it.cardId != CardId.BODY_TEMPERATURE }
            if (!spo2Granted) list = list.filter { it.cardId != CardId.OXYGEN_SATURATION }
            return list
        }
        VitalsCardState(
            isManagingCards = isManaging,
            cardConfigurations = cardConfig.filteredForPermission(),
            pendingConfiguration = pendingCardConfig?.filteredForPermission(),
        )
    }
}

/**
 * Creates the vitals chart-state flow: merges edit-mode/pending state from the chart delegate
 * with the persisted configuration. Charts have no permission gating and no display mode.
 */
internal fun createVitalsChartStateFlow(
    chartManagementDelegate: VitalsChartManagementDelegate,
    vitalsLayoutRepository: VitalsLayoutRepository,
): Flow<VitalsChartState> =
    combine(
        chartManagementDelegate.isManagingCharts,
        chartManagementDelegate.pendingConfigs,
        vitalsLayoutRepository.vitalsChartConfigurations(),
    ) { isManaging, pendingChartConfig, chartConfig ->
        VitalsChartState(
            isManagingCharts = isManaging,
            chartConfigurations = chartConfig,
            pendingConfiguration = pendingChartConfig,
        )
    }
