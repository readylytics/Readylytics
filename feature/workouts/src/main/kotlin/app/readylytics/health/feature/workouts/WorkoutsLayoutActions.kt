package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId

interface WorkoutsLayoutActions {
    val layoutActionHandler: WorkoutsLayoutActionHandler

    fun toggleWorkoutsManagement() = layoutActionHandler.toggleWorkoutsManagement()

    fun onCancelWorkoutsManagement() = layoutActionHandler.onCancelWorkoutsManagement()

    fun onToggleCardVisibility(
        cardId: CardId,
        visible: Boolean,
    ) = layoutActionHandler.onToggleCardVisibility(cardId, visible)

    fun onReorderCards(newOrder: List<CardConfiguration>) = layoutActionHandler.onReorderCards(newOrder)

    fun onWorkoutsCardDisplayModeChanged(
        cardId: CardId,
        mode: DashboardCardDisplayMode,
    ) = layoutActionHandler.onWorkoutsCardDisplayModeChanged(cardId, mode)

    fun onToggleChartVisibility(
        chartId: WorkoutChartId,
        visible: Boolean,
    ) = layoutActionHandler.onToggleChartVisibility(chartId, visible)

    fun onReorderCharts(newOrder: List<WorkoutChartConfiguration>) = layoutActionHandler.onReorderCharts(newOrder)

    fun onToggleHistoryVisibility(
        historyId: WorkoutHistoryId,
        visible: Boolean,
    ) = layoutActionHandler.onToggleHistoryVisibility(historyId, visible)

    fun onReorderHistory(newOrder: List<WorkoutHistoryConfiguration>) = layoutActionHandler.onReorderHistory(newOrder)

    fun onResetWorkoutsToDefaults() = layoutActionHandler.onResetWorkoutsToDefaults()
}
