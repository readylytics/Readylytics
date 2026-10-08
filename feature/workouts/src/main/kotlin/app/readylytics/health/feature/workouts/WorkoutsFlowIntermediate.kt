package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Immutable
internal data class WorkoutsCardState(
    val isManagingCards: Boolean,
    val cardConfigurations: List<CardConfiguration>,
    val pendingConfiguration: List<CardConfiguration>?,
)

@Immutable
internal data class WorkoutsChartState(
    val isManagingCharts: Boolean,
    val chartConfigurations: List<WorkoutChartConfiguration>,
    val pendingConfiguration: List<WorkoutChartConfiguration>?,
)

@Immutable
internal data class WorkoutsHistoryState(
    val isManagingHistory: Boolean,
    val historyConfigurations: List<WorkoutHistoryConfiguration>,
    val pendingConfiguration: List<WorkoutHistoryConfiguration>?,
)

internal fun createWorkoutsCardStateFlow(
    cardManagementDelegate: CardManagementDelegate,
    workoutsLayoutRepository: WorkoutsLayoutRepository,
): Flow<WorkoutsCardState> =
    combine(
        cardManagementDelegate.isManagingCards,
        cardManagementDelegate.pendingConfigs,
        workoutsLayoutRepository.workoutCardConfigurations(),
    ) { isManaging, pendingCardConfig, cardConfig ->
        WorkoutsCardState(
            isManagingCards = isManaging,
            cardConfigurations = cardConfig,
            pendingConfiguration = pendingCardConfig,
        )
    }

internal fun createWorkoutsChartStateFlow(
    chartManagementDelegate: LayoutManagementDelegate<WorkoutChartConfiguration, WorkoutChartId>,
    workoutsLayoutRepository: WorkoutsLayoutRepository,
): Flow<WorkoutsChartState> =
    combine(
        chartManagementDelegate.isManaging,
        chartManagementDelegate.pendingConfigs,
        workoutsLayoutRepository.workoutChartConfigurations(),
    ) { isManaging, pendingChartConfig, chartConfig ->
        WorkoutsChartState(
            isManagingCharts = isManaging,
            chartConfigurations = chartConfig,
            pendingConfiguration = pendingChartConfig,
        )
    }

internal fun createWorkoutsHistoryStateFlow(
    historyManagementDelegate: LayoutManagementDelegate<WorkoutHistoryConfiguration, WorkoutHistoryId>,
    workoutsLayoutRepository: WorkoutsLayoutRepository,
): Flow<WorkoutsHistoryState> =
    combine(
        historyManagementDelegate.isManaging,
        historyManagementDelegate.pendingConfigs,
        workoutsLayoutRepository.workoutHistoryConfigurations(),
    ) { isManaging, pendingHistoryConfig, historyConfig ->
        WorkoutsHistoryState(
            isManagingHistory = isManaging,
            historyConfigurations = historyConfig,
            pendingConfiguration = pendingHistoryConfig,
        )
    }

@Immutable
internal data class WorkoutsLayoutState(
    val cards: WorkoutsCardState,
    val charts: WorkoutsChartState,
    val history: WorkoutsHistoryState,
)

/** Cheap, frequently-ticking UI inputs merged onto the expensive data state in a single copy (UI-101). */
@Immutable
internal data class WorkoutsChromeState(
    val isSyncing: Boolean,
    val isRangeChanging: Boolean,
    val trainingLoadMetric: TrainingLoadMetric,
    val layout: WorkoutsLayoutState,
)

internal fun WorkoutsChromeState.applyTo(state: WorkoutsUiState): WorkoutsUiState =
    state.copy(
        isLoading = isSyncing && state.latestSummary == null && state.recentWorkouts.isEmpty(),
        isRefreshing = isSyncing,
        isRangeChanging = isRangeChanging,
        selectedTrainingLoadMetric = trainingLoadMetric,
        cardConfigurations = layout.cards.pendingConfiguration ?: layout.cards.cardConfigurations,
        isManagingCards = layout.cards.isManagingCards,
        chartConfigurations = layout.charts.pendingConfiguration ?: layout.charts.chartConfigurations,
        isManagingCharts = layout.charts.isManagingCharts,
        historyConfigurations = layout.history.pendingConfiguration ?: layout.history.historyConfigurations,
        isManagingHistory = layout.history.isManagingHistory,
    )

internal fun createWorkoutsLayoutStateFlow(
    cards: Flow<WorkoutsCardState>,
    charts: Flow<WorkoutsChartState>,
    history: Flow<WorkoutsHistoryState>,
): Flow<WorkoutsLayoutState> = combine(cards, charts, history) { c, ch, h -> WorkoutsLayoutState(c, ch, h) }
