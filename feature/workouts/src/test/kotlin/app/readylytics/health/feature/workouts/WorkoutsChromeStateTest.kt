package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.model.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WorkoutsChromeStateTest {
    private val cards = SettingsDefaults.DEFAULT_WORKOUT_CARDS
    private val charts = SettingsDefaults.DEFAULT_WORKOUT_CHARTS
    private val history = SettingsDefaults.DEFAULT_WORKOUT_HISTORY

    /** The pre-UI-101 chain from WorkoutsViewModel, kept as the equivalence reference. */
    private fun legacyChain(
        state: WorkoutsUiState,
        chrome: WorkoutsChromeState,
    ): WorkoutsUiState {
        val syncing = chrome.isSyncing
        val cardState = chrome.layout.cards
        val chartState = chrome.layout.charts
        val historyState = chrome.layout.history
        return state
            .copy(
                isLoading = syncing && (state.latestSummary == null && state.recentWorkouts.isEmpty()),
                isRefreshing = syncing,
            ).copy(isRangeChanging = chrome.isRangeChanging)
            .copy(selectedTrainingLoadMetric = chrome.trainingLoadMetric)
            .copy(
                cardConfigurations = cardState.pendingConfiguration ?: cardState.cardConfigurations,
                isManagingCards = cardState.isManagingCards,
            ).copy(
                chartConfigurations = chartState.pendingConfiguration ?: chartState.chartConfigurations,
                isManagingCharts = chartState.isManagingCharts,
            ).copy(
                historyConfigurations = historyState.pendingConfiguration ?: historyState.historyConfigurations,
                isManagingHistory = historyState.isManagingHistory,
            )
    }

    private fun layout(managing: Boolean) =
        WorkoutsLayoutState(
            cards = WorkoutsCardState(managing, cards, if (managing) cards.reversed() else null),
            charts = WorkoutsChartState(managing, charts, if (managing) charts.reversed() else null),
            history = WorkoutsHistoryState(managing, history, if (managing) history.reversed() else null),
        )

    @Test
    fun `applyTo matches the legacy six-copy chain for every chrome input`() {
        val emptyState = WorkoutsUiState()
        val loadedState = emptyState.copy(latestSummary = DailySummary(date = LocalDate.of(2026, 10, 8)))
        val flags = listOf(false, true)
        val chromes =
            flags.flatMap { syncing ->
                flags.flatMap { rangeChanging ->
                    TrainingLoadMetric.entries.flatMap { metric ->
                        flags.map { managing -> WorkoutsChromeState(syncing, rangeChanging, metric, layout(managing)) }
                    }
                }
            }
        val states = listOf(emptyState, loadedState)
        assertEquals(2 * 2 * TrainingLoadMetric.entries.size * 2, chromes.size)

        states.forEach { state ->
            chromes.forEach { chrome -> assertEquals(legacyChain(state, chrome), chrome.applyTo(state)) }
        }
    }

    @Test
    fun `syncing with no data yet shows loading, with data only refreshing`() {
        val chrome =
            WorkoutsChromeState(
                isSyncing = true,
                isRangeChanging = false,
                trainingLoadMetric = TrainingLoadMetric.ACWR,
                layout = layout(managing = false),
            )

        val empty = chrome.applyTo(WorkoutsUiState())
        assertEquals(true, empty.isLoading)
        assertEquals(true, empty.isRefreshing)

        val loaded = chrome.applyTo(WorkoutsUiState(latestSummary = DailySummary(date = LocalDate.of(2026, 10, 8))))
        assertEquals(false, loaded.isLoading)
        assertEquals(true, loaded.isRefreshing)
    }
}
