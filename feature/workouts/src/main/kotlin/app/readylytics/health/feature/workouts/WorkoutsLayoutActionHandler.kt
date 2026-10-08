package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.dashboard.CardManagementEvent
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId

class WorkoutsLayoutActionHandler(
    private val cardManagementDelegate: CardManagementDelegate,
    private val chartManagementDelegate: LayoutManagementDelegate<WorkoutChartConfiguration, WorkoutChartId>,
    private val historyManagementDelegate: LayoutManagementDelegate<WorkoutHistoryConfiguration, WorkoutHistoryId>,
    private val currentUiState: () -> WorkoutsUiState,
) {
    fun toggleWorkoutsManagement() {
        val s = currentUiState()
        if (s.isManagingCards) {
            cardManagementDelegate.saveChanges()
        } else {
            cardManagementDelegate.enterEditMode(s.cardConfigurations)
        }
        if (s.isManagingCharts) {
            chartManagementDelegate.saveChanges()
        } else {
            chartManagementDelegate.enterEditMode(s.chartConfigurations)
        }
        if (s.isManagingHistory) {
            historyManagementDelegate.saveChanges()
        } else {
            historyManagementDelegate.enterEditMode(s.historyConfigurations)
        }
    }

    fun onCancelWorkoutsManagement() {
        cardManagementDelegate.cancelChanges()
        chartManagementDelegate.cancelChanges()
        historyManagementDelegate.cancelChanges()
    }

    fun onToggleCardVisibility(
        cardId: CardId,
        visible: Boolean,
    ) = cardManagementDelegate.onToggleCardVisibility(currentUiState().cardConfigurations, cardId, visible)

    fun onReorderCards(newOrder: List<CardConfiguration>) =
        cardManagementDelegate.onReorderCards(currentUiState().cardConfigurations, newOrder)

    fun onWorkoutsCardDisplayModeChanged(
        cardId: CardId,
        mode: DashboardCardDisplayMode,
    ) = cardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))

    fun onToggleChartVisibility(
        chartId: WorkoutChartId,
        visible: Boolean,
    ) = chartManagementDelegate.onToggleVisibility(currentUiState().chartConfigurations, chartId, visible)

    fun onReorderCharts(newOrder: List<WorkoutChartConfiguration>) =
        chartManagementDelegate.onReorder(currentUiState().chartConfigurations, newOrder)

    fun onToggleHistoryVisibility(
        historyId: WorkoutHistoryId,
        visible: Boolean,
    ) = historyManagementDelegate.onToggleVisibility(currentUiState().historyConfigurations, historyId, visible)

    fun onReorderHistory(newOrder: List<WorkoutHistoryConfiguration>) =
        historyManagementDelegate.onReorder(currentUiState().historyConfigurations, newOrder)

    fun onResetWorkoutsToDefaults() {
        cardManagementDelegate.onResetToDefaults()
        chartManagementDelegate.onResetToDefaults()
        historyManagementDelegate.onResetToDefaults()
    }
}
