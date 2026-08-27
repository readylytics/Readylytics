package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepChartId
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardManagementDelegate
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardManagementDelegate
import app.readylytics.health.feature.sleep.overview.createSleepChartStateFlow
import app.readylytics.health.feature.sleep.overview.createSleepMetricCardStateFlow
import app.readylytics.health.feature.sleep.overview.createSleepTopCardStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

@Immutable
data class SleepLayoutState(
    val sleepTopCardConfigurations: List<SleepTopCardConfiguration> = SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
    val isManagingSleepTopCards: Boolean = false,
    val sleepChartConfigurations: List<SleepChartConfiguration> = SettingsDefaults.DEFAULT_SLEEP_CHARTS,
    val isManagingSleepCharts: Boolean = false,
    val sleepMetricCardConfigurations: List<SleepMetricCardConfiguration> = SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
    val isManagingSleepMetricCards: Boolean = false,
) {
    val isManagingSleepLayout: Boolean
        get() = isManagingSleepTopCards || isManagingSleepCharts || isManagingSleepMetricCards
}

class SleepLayoutDelegate(
    sleepLayoutRepository: SleepLayoutRepository,
    scope: CoroutineScope,
) {
    private val sleepTopCardManagementDelegate =
        SleepTopCardManagementDelegate(
            defaultConfigurations = SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
            persist = sleepLayoutRepository::updateSleepTopCardConfigurations,
            scope = scope,
        )

    private val sleepChartManagementDelegate =
        LayoutManagementDelegate(
            defaultConfigurations = SettingsDefaults.DEFAULT_SLEEP_CHARTS,
            persist = sleepLayoutRepository::updateSleepChartConfigurations,
            scope = scope,
            withVisibility = { config, visible -> config.copy(isVisible = visible) },
            withPosition = { config, pos -> config.copy(position = pos) },
        )

    private val sleepMetricCardManagementDelegate =
        SleepMetricCardManagementDelegate(
            defaultConfigurations = SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
            persist = sleepLayoutRepository::updateSleepMetricCardConfigurations,
            scope = scope,
        )

    internal val sleepTopCardStateFlow =
        createSleepTopCardStateFlow(
            delegate = sleepTopCardManagementDelegate,
            repository = sleepLayoutRepository,
        ).distinctUntilChanged()

    internal val sleepChartStateFlow =
        createSleepChartStateFlow(
            delegate = sleepChartManagementDelegate,
            repository = sleepLayoutRepository,
        ).distinctUntilChanged()

    internal val sleepMetricCardStateFlow =
        createSleepMetricCardStateFlow(
            delegate = sleepMetricCardManagementDelegate,
            repository = sleepLayoutRepository,
        ).distinctUntilChanged()

    val layoutStateFlow: StateFlow<SleepLayoutState> =
        combine(
            sleepTopCardStateFlow,
            sleepChartStateFlow,
            sleepMetricCardStateFlow,
        ) { topCardState, chartState, metricCardState ->
            SleepLayoutState(
                sleepTopCardConfigurations = topCardState.topCardConfigurations,
                isManagingSleepTopCards = topCardState.isManagingTopCards,
                sleepChartConfigurations = chartState.chartConfigurations,
                isManagingSleepCharts = chartState.isManagingCharts,
                sleepMetricCardConfigurations = metricCardState.metricCardConfigurations,
                isManagingSleepMetricCards = metricCardState.isManagingMetricCards,
            )
        }.distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = SleepLayoutState(),
            )

    fun toggleSleepLayoutManagement(
        isManaging: Boolean,
        currentTopCards: List<SleepTopCardConfiguration>,
        currentCharts: List<SleepChartConfiguration>,
        currentMetricCards: List<SleepMetricCardConfiguration>,
    ) {
        if (isManaging) {
            sleepTopCardManagementDelegate.saveChanges()
            sleepChartManagementDelegate.saveChanges()
            sleepMetricCardManagementDelegate.saveChanges()
        } else {
            sleepTopCardManagementDelegate.enterEditMode(currentTopCards)
            sleepChartManagementDelegate.enterEditMode(currentCharts)
            sleepMetricCardManagementDelegate.enterEditMode(currentMetricCards)
        }
    }

    fun onCancelSleepLayoutManagement() {
        sleepTopCardManagementDelegate.cancelChanges()
        sleepChartManagementDelegate.cancelChanges()
        sleepMetricCardManagementDelegate.cancelChanges()
    }

    fun onResetSleepLayoutToDefaults() {
        sleepTopCardManagementDelegate.onResetToDefaults()
        sleepChartManagementDelegate.onResetToDefaults()
        sleepMetricCardManagementDelegate.onResetToDefaults()
    }

    fun onToggleSleepTopCardVisibility(
        currentConfigs: List<SleepTopCardConfiguration>,
        cardId: SleepTopCardId,
        visible: Boolean,
    ) {
        sleepTopCardManagementDelegate.onToggleVisibility(
            currentConfigs,
            cardId,
            visible,
        )
    }

    fun onReorderSleepTopCards(
        currentConfigs: List<SleepTopCardConfiguration>,
        newOrder: List<SleepTopCardConfiguration>,
    ) {
        sleepTopCardManagementDelegate.onReorder(
            currentConfigs,
            newOrder,
        )
    }

    fun onToggleSleepChartVisibility(
        currentConfigs: List<SleepChartConfiguration>,
        chartId: SleepChartId,
        visible: Boolean,
    ) {
        sleepChartManagementDelegate.onToggleVisibility(
            currentConfigs,
            chartId,
            visible,
        )
    }

    fun onReorderSleepCharts(
        currentConfigs: List<SleepChartConfiguration>,
        newOrder: List<SleepChartConfiguration>,
    ) {
        sleepChartManagementDelegate.onReorder(
            currentConfigs,
            newOrder,
        )
    }

    fun onToggleSleepMetricCardVisibility(
        currentConfigs: List<SleepMetricCardConfiguration>,
        cardId: SleepMetricCardId,
        visible: Boolean,
    ) {
        sleepMetricCardManagementDelegate.onToggleVisibility(
            currentConfigs,
            cardId,
            visible,
        )
    }

    fun onReorderSleepMetricCards(
        currentConfigs: List<SleepMetricCardConfiguration>,
        newOrder: List<SleepMetricCardConfiguration>,
    ) {
        sleepMetricCardManagementDelegate.onReorder(
            currentConfigs,
            newOrder,
        )
    }

    fun onDisplayModeChanged(
        topCardId: SleepTopCardId? = null,
        metricCardId: SleepMetricCardId? = null,
        mode: DashboardCardDisplayMode?,
    ) {
        topCardId?.let { sleepTopCardManagementDelegate.onDisplayModeChanged(it, mode) }
        metricCardId?.let { sleepMetricCardManagementDelegate.onDisplayModeChanged(it, mode) }
    }
}
