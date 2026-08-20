package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.domain.layout.LayoutManagementDelegate
import app.readylytics.health.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.domain.workouts.WorkoutChartId
import app.readylytics.health.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.domain.workouts.WorkoutHistoryId
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
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
