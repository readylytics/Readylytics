package app.readylytics.health.feature.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.rememberManageLayoutState
import app.readylytics.health.core.ui.components.reorder.ReorderableGrid
import app.readylytics.health.core.ui.components.reorder.ReorderableList
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.feature.sleep.overview.SleepManagementBottomSheet
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun SleepRoute(viewModel: SleepViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val circadian by viewModel.circadianConsistencyFlow.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    SleepScreen(
        uiState = uiState,
        circadianConsistency = circadian,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = viewModel::onDateSelected,
        onTrendRangeSelected = viewModel::onTrendRangeSelected,
        earliestDate = earliestDate,
        onToggleSleepManagement = viewModel::toggleSleepLayoutManagement,
        onCancelSleepManagement = viewModel::onCancelSleepLayoutManagement,
        onToggleSleepTopCardVisibility = viewModel::onToggleSleepTopCardVisibility,
        onReorderSleepTopCards = viewModel::onReorderSleepTopCards,
        onSleepTopCardDisplayModeChanged = viewModel::onSleepTopCardDisplayModeChanged,
        onToggleSleepChartVisibility = viewModel::onToggleSleepChartVisibility,
        onReorderSleepCharts = viewModel::onReorderSleepCharts,
        onToggleSleepMetricCardVisibility = viewModel::onToggleSleepMetricCardVisibility,
        onReorderSleepMetricCards = viewModel::onReorderSleepMetricCards,
        onSleepMetricCardDisplayModeChanged = viewModel::onSleepMetricCardDisplayModeChanged,
        onResetSleepLayoutToDefaults = viewModel::onResetSleepLayoutToDefaults,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    uiState: SleepUiState,
    circadianConsistency: CircadianConsistencyResult,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    onTrendRangeSelected: (TimeRange) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
    onToggleSleepManagement: () -> Unit = {},
    onCancelSleepManagement: () -> Unit = {},
    onToggleSleepTopCardVisibility: (SleepTopCardId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepTopCards: (List<SleepTopCardConfiguration>) -> Unit = {},
    onSleepTopCardDisplayModeChanged: (SleepTopCardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
    onToggleSleepChartVisibility: (SleepChartId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepCharts: (List<SleepChartConfiguration>) -> Unit = {},
    onToggleSleepMetricCardVisibility: (SleepMetricCardId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepMetricCards: (List<SleepMetricCardConfiguration>) -> Unit = {},
    onSleepMetricCardDisplayModeChanged: (SleepMetricCardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
    onResetSleepLayoutToDefaults: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val manageState = rememberManageLayoutState()

    val singleSessionVisual = uiState.latestSession
    val (trendScrollState, trendZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedTrendRange.days,
            key = uiState.selectedTrendRange,
        )

    val visibleCharts =
        remember(uiState.sleepChartConfigurations) {
            uiState.sleepChartConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }
    val visibleMetricCards =
        remember(uiState.sleepMetricCardConfigurations) {
            uiState.sleepMetricCardConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }

    val topCardDataMap =
        rememberSleepTopCardDataMap(
            uiState = uiState,
            singleSessionVisual = singleSessionVisual,
            onDisplayModeChanged = { id, mode -> onSleepTopCardDisplayModeChanged(id, mode) },
        )

    val trendChartDataMap: Map<SleepChartId, @Composable (SleepChartConfiguration) -> Unit> =
        mapOf(
            SleepChartId.SLEEP_DURATION_TREND to
                @Composable { _: SleepChartConfiguration ->
                    if (uiState.isLoading) {
                        SleepTrendSkeleton()
                    } else {
                        SleepTrendCard(
                            selectedRange = uiState.selectedTrendRange,
                            startOffsetPoints = uiState.trendStartOffsetPoints,
                            durationSpanPoints = uiState.trendDurationSpanPoints,
                            actualDurationPoints = uiState.trendActualDurationPoints,
                            trendDays = uiState.trendDays,
                            rangeStartMs = uiState.trendRangeStartMs,
                            scoringZoneId = uiState.trendScoringZoneId,
                            scrollState = trendScrollState,
                            zoomState = trendZoomState,
                            parentScrollInProgress = { scrollState.isScrollInProgress },
                            actualDurationSummary = uiState.trendActualDurationSummary,
                        )
                    }
                },
        )

    Box(modifier = modifier.fillMaxSize()) {
        if (manageState.isManageOpen) {
            SleepManagementBottomSheet(
                topCardConfigurations = uiState.sleepTopCardConfigurations,
                chartConfigurations = uiState.sleepChartConfigurations,
                metricCardConfigurations = uiState.sleepMetricCardConfigurations,
                onTopCardVisibilityChanged = onToggleSleepTopCardVisibility,
                onChartVisibilityChanged = onToggleSleepChartVisibility,
                onMetricCardVisibilityChanged = onToggleSleepMetricCardVisibility,
                onTopCardDisplayModeChanged = onSleepTopCardDisplayModeChanged,
                onMetricCardDisplayModeChanged = onSleepMetricCardDisplayModeChanged,
                onResetToDefaults = onResetSleepLayoutToDefaults,
                onDismiss = manageState.closeManage,
                sheetState = manageState.sheetState,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onDateSelected = onDateSelected,
                    earliestDate = earliestDate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

            ReorderableGrid(
                items = uiState.sleepTopCardConfigurations,
                dataMap = topCardDataMap,
                isEditing = uiState.isManagingSleepTopCards,
                onItemReorder = onReorderSleepTopCards,
                onItemDropToRemove = { onToggleSleepTopCardVisibility(it, false) },
                fullWidthIds = SLEEP_TOP_CARD_FULL_WIDTH_IDS,
                verticalSpacing = MaterialTheme.spacing.pageSectionGapSmall,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            )

            if (visibleCharts.any { it.chartId == SleepChartId.SLEEP_DURATION_TREND }) {
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

                SectionHeader(
                    title = stringResource(R.string.sleep_trend_section_title),
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
                            selected = uiState.selectedTrendRange == range,
                            onClick = { onTrendRangeSelected(range) },
                            enabled = !uiState.isLoading,
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = TimeRange.entries.size,
                                ),
                            label = { Text(range.label) },
                        )
                    }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                ReorderableList(
                    items = uiState.sleepChartConfigurations,
                    dataMap = trendChartDataMap,
                    isEditing = uiState.isManagingSleepCharts,
                    onItemReorder = onReorderSleepCharts,
                    onItemHide = { onToggleSleepChartVisibility(it, false) },
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                )
            }

            if (visibleMetricCards.isNotEmpty()) {
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

                SectionHeader(title = stringResource(R.string.sleep_metrics_title))
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                if (uiState.isLoading) {
                    MetricsGridSkeleton(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal))
                } else {
                    ReorderableGrid(
                        items = uiState.sleepMetricCardConfigurations,
                        dataMap =
                            rememberSleepMetricCardDataMap(
                                uiState,
                                circadianConsistency,
                                singleSessionVisual,
                                onDisplayModeChanged = { id, mode -> onSleepMetricCardDisplayModeChanged(id, mode) },
                            ),
                        isEditing = uiState.isManagingSleepMetricCards,
                        onItemReorder = onReorderSleepMetricCards,
                        onItemDropToRemove = { onToggleSleepMetricCardVisibility(it, false) },
                        fullWidthIds = emptySet(),
                        verticalSpacing = MaterialTheme.spacing.pageSectionGapSmall,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

            StatusLegend()

            if (!uiState.isManagingSleepLayout) {
                FilledTonalButton(
                    onClick = onToggleSleepManagement,
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

        EditModeFab(
            isVisible = uiState.isManagingSleepLayout,
            onDoneClick = onToggleSleepManagement,
            onCancelClick = onCancelSleepManagement,
            onManageClick = manageState.openManage,
            modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.pageHorizontal),
        )
    }
}

@Composable
private fun MetricsGridSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
    }
}
