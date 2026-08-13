package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.ScreenHeaderSection
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.CardConfigurationsList
import app.readylytics.health.core.ui.components.CardDataMap
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.ReorderableCardGrid
import app.readylytics.health.core.ui.components.ReorderableWorkoutChartList
import app.readylytics.health.core.ui.components.ReorderableWorkoutHistoryList
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.WorkoutChartConfigurationsList
import app.readylytics.health.core.ui.components.WorkoutChartDataMap
import app.readylytics.health.core.ui.components.WorkoutHistoryConfigurationsList
import app.readylytics.health.core.ui.components.WorkoutHistoryDataMap
import app.readylytics.health.core.ui.components.rememberManageLayoutState
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.core.ui.R as CoreUiR

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
    onToggleCardVisibility: (app.readylytics.health.domain.dashboard.CardId, Boolean) -> Unit = { _, _ -> },
    onReorderCards: (List<app.readylytics.health.domain.dashboard.CardConfiguration>) -> Unit = {},
    onWorkoutsCardDisplayModeChanged: (
        app.readylytics.health.domain.dashboard.CardId,
        app.readylytics.health.domain.dashboard.DashboardCardDisplayMode,
    ) -> Unit = { _, _ -> },
    onToggleChartVisibility: (app.readylytics.health.domain.workouts.WorkoutChartId, Boolean) -> Unit = { _, _ -> },
    onReorderCharts: (List<app.readylytics.health.domain.workouts.WorkoutChartConfiguration>) -> Unit = {},
    onToggleHistoryVisibility: (app.readylytics.health.domain.workouts.WorkoutHistoryId, Boolean) -> Unit = { _, _ -> },
    onReorderHistory: (List<app.readylytics.health.domain.workouts.WorkoutHistoryConfiguration>) -> Unit = {},
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
        CardDataMap(
            buildWorkoutsCardDataMap(
                uiState = uiState,
                isEditing = uiState.isManagingCards,
                onWorkoutsCardDisplayModeChanged = onWorkoutsCardDisplayModeChanged,
            ),
        )
    val chartDataMap =
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
    val historyDataMap =
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
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onDateSelected = onDateSelected,
                    earliestDate = earliestDate,
                    enabled = !isDisabled,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                            .padding(top = MaterialTheme.spacing.pageTop),
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
                CardLoader(
                    isLoading = uiState.isLoading,
                    skeleton = { WorkoutsCardsSkeleton() },
                    content = {
                        ReorderableCardGrid(
                            cardConfigurations = CardConfigurationsList(uiState.cardConfigurations),
                            cardDataMap = cardDataMap,
                            isEditing = uiState.isManagingCards,
                            onCardRemove = { cardId -> onToggleCardVisibility(cardId, false) },
                            onCardReorder = onReorderCards,
                            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                            additionalFullWidthIds = setOf(CardId.RAS_DAILY),
                        )
                    },
                )

                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
                SectionHeader(
                    title = stringResource(R.string.workout_stats_acwr_title),
                    enabled = !uiState.isLoading,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
                SingleChoiceSegmentedButtonRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    TimeRange.entries.forEachIndexed { index, range ->
                        SegmentedButton(
                            selected = selectedRange == range,
                            onClick = { onRangeSelected(range) },
                            enabled = !uiState.isLoading && !uiState.isRangeChanging,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = TimeRange.entries.size),
                            label = { Text(range.label) },
                        )
                    }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                CardLoader(
                    isLoading = uiState.isLoading || uiState.isRangeChanging,
                    skeleton = {
                        SkeletonCard(
                            height = 220.dp,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                        )
                    },
                    content = {
                        ReorderableWorkoutChartList(
                            chartConfigurations = WorkoutChartConfigurationsList(uiState.chartConfigurations),
                            chartDataMap = chartDataMap,
                            isEditing = uiState.isManagingCharts,
                            onChartHide = { chartId -> onToggleChartVisibility(chartId, false) },
                            onChartReorder = onReorderCharts,
                        )
                    },
                )

                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

                CardLoader(
                    isLoading = uiState.isLoading,
                    skeleton = { WorkoutListSectionSkeleton() },
                    content = {
                        ReorderableWorkoutHistoryList(
                            historyConfigurations = WorkoutHistoryConfigurationsList(uiState.historyConfigurations),
                            historyDataMap = historyDataMap,
                            isEditing = uiState.isManagingHistory,
                            onHistoryHide = { historyId -> onToggleHistoryVisibility(historyId, false) },
                            onHistoryReorder = onReorderHistory,
                        )
                    },
                )

                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

                StatusLegend()

                if (!uiState.isManagingWorkoutsLayout) {
                    FilledTonalButton(
                        onClick = onToggleWorkoutsManagement,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MaterialTheme.spacing.pageHorizontal,
                                    vertical = MaterialTheme.spacing.pageSectionGap,
                                ),
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Text(
                            text = stringResource(CoreUiR.string.action_customize),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
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

@Composable
private fun WorkoutsCardsSkeleton() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal, vertical = MaterialTheme.spacing.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
        ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WorkoutListSectionSkeleton() {
    Column(
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.pageSectionGap,
            ),
    ) {
        repeat(3) {
            SkeletonCard(
                height = 80.dp,
                modifier = Modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacing.small),
            )
        }
    }
}
