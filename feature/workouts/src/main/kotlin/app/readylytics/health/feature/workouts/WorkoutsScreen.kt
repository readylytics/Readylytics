package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.ScreenHeaderSection
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.CardDataMap
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.WorkoutChartDataMap
import app.readylytics.health.core.ui.components.WorkoutHistoryDataMap
import app.readylytics.health.core.ui.components.rememberManageLayoutState

@Composable
fun WorkoutsRoute(
    viewModel: WorkoutsViewModel = hiltViewModel(),
    onWorkoutClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()
    val selectedRange by viewModel.selectedRange.collectAsStateWithLifecycle()
    WorkoutsScreen(
        uiState = uiState,
        selectedRange = selectedRange,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onPreviousPage = viewModel::onPreviousPage,
        onNextPage = viewModel::onNextPage,
        onDateSelected = viewModel::onDateSelected,
        earliestDate = earliestDate,
        onWorkoutClick = onWorkoutClick,
        onToggleWorkoutsManagement = viewModel::toggleWorkoutsManagement,
        onCancelWorkoutsManagement = viewModel::onCancelWorkoutsManagement,
        onToggleCardVisibility = viewModel::onToggleCardVisibility,
        onReorderCards = viewModel::onReorderCards,
        onWorkoutsCardDisplayModeChanged = viewModel::onWorkoutsCardDisplayModeChanged,
        onToggleChartVisibility = viewModel::onToggleChartVisibility,
        onReorderCharts = viewModel::onReorderCharts,
        onToggleHistoryVisibility = viewModel::onToggleHistoryVisibility,
        onReorderHistory = viewModel::onReorderHistory,
        onResetWorkoutsToDefaults = viewModel::onResetWorkoutsToDefaults,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
fun WorkoutsScreen(
    uiState: WorkoutsUiState,
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onWorkoutClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
    onToggleWorkoutsManagement: () -> Unit = {},
    onCancelWorkoutsManagement: () -> Unit = {},
    onToggleCardVisibility: (app.readylytics.health.core.model.domain.dashboard.CardId, Boolean) -> Unit = { _, _ -> },
    onReorderCards: (List<app.readylytics.health.core.model.domain.dashboard.CardConfiguration>) -> Unit = {},
    onWorkoutsCardDisplayModeChanged: (
        app.readylytics.health.core.model.domain.dashboard.CardId,
        app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode,
    ) -> Unit = { _, _ -> },
    onToggleChartVisibility: (
        app.readylytics.health.core.model.domain.workouts.WorkoutChartId,
        Boolean,
    ) -> Unit = { _, _ -> },
    onReorderCharts: (List<app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration>) -> Unit = {},
    onToggleHistoryVisibility: (
        app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId,
        Boolean,
    ) -> Unit = { _, _ -> },
    onReorderHistory: (
        List<app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration>,
    ) -> Unit = {},
    onResetWorkoutsToDefaults: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val manageState = rememberManageLayoutState()

    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = uiState.selectedRange,
        )

    val cardDataMap =
        remember(uiState, onWorkoutsCardDisplayModeChanged) {
            CardDataMap(
                buildWorkoutsCardDataMap(
                    uiState = uiState,
                    isEditing = uiState.isManagingCards,
                    onWorkoutsCardDisplayModeChanged = onWorkoutsCardDisplayModeChanged,
                ),
            )
        }
    val chartDataMap =
        remember(uiState, selectedRange, chartScrollState, chartZoomState) {
            WorkoutChartDataMap(
                buildWorkoutsChartDataMap(
                    uiState = uiState,
                    rangeDays = uiState.selectedRange.days,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    granularity = selectedRange.granularity,
                    parentScrollInProgress = { scrollState.isScrollInProgress },
                ),
            )
        }
    val historyDataMap =
        remember(uiState, onPreviousPage, onNextPage, onWorkoutClick) {
            WorkoutHistoryDataMap(
                buildWorkoutsHistoryDataMap(
                    uiState = uiState,
                    currentPage = uiState.currentPage,
                    totalPages = uiState.totalPages,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onWorkoutClick = onWorkoutClick,
                ),
            )
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (manageState.isManageOpen) {
            WorkoutsManagementBottomSheet(
                cardConfigurations = uiState.cardConfigurations,
                chartConfigurations = uiState.chartConfigurations,
                historyConfigurations = uiState.historyConfigurations,
                onCardVisibilityChanged = onToggleCardVisibility,
                onChartVisibilityChanged = onToggleChartVisibility,
                onHistoryVisibilityChanged = onToggleHistoryVisibility,
                onCardDisplayModeChanged = onWorkoutsCardDisplayModeChanged,
                onResetToDefaults = onResetWorkoutsToDefaults,
                onDismiss = manageState.closeManage,
                sheetState = manageState.sheetState,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeaderSection(isLoading = uiState.isRefreshing) { isDisabled ->
                DateSwitcherSection(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onDateSelected = onDateSelected,
                    earliestDate = earliestDate,
                    isDisabled = isDisabled,
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(
                            top = MaterialTheme.spacing.pageSectionGapSmall,
                            bottom = MaterialTheme.spacing.pageBottom,
                        ),
            ) {
                CardsDisplaySection(
                    uiState = uiState,
                    cardDataMap = cardDataMap,
                    isManagingCards = uiState.isManagingCards,
                    onToggleCardVisibility = onToggleCardVisibility,
                    onReorderCards = onReorderCards,
                )

                AcwrRangeSection(
                    selectedRange = selectedRange,
                    isLoading = uiState.isLoading,
                    isRangeChanging = uiState.isRangeChanging,
                    onRangeSelected = onRangeSelected,
                )

                ChartsDisplaySection(
                    uiState = uiState,
                    chartDataMap = chartDataMap,
                    isManagingCharts = uiState.isManagingCharts,
                    onToggleChartVisibility = onToggleChartVisibility,
                    onReorderCharts = onReorderCharts,
                )

                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

                HistoryDisplaySection(
                    uiState = uiState,
                    historyDataMap = historyDataMap,
                    isManagingHistory = uiState.isManagingHistory,
                    onToggleHistoryVisibility = onToggleHistoryVisibility,
                    onReorderHistory = onReorderHistory,
                )

                StatusAndFooterSection(
                    isManagingWorkoutsLayout = uiState.isManagingWorkoutsLayout,
                    onToggleWorkoutsManagement = onToggleWorkoutsManagement,
                )
            }
        }

        EditModeFab(
            isVisible = uiState.isManagingWorkoutsLayout,
            onDoneClick = onToggleWorkoutsManagement,
            onCancelClick = onCancelWorkoutsManagement,
            onManageClick = manageState.openManage,
            modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.pageHorizontal),
        )
    }
}
