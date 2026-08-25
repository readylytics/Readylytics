package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.CardConfigurationsList
import app.readylytics.health.core.ui.components.CardDataMap
import app.readylytics.health.core.ui.components.ReorderableCardGrid
import app.readylytics.health.core.ui.components.ReorderableWorkoutChartList
import app.readylytics.health.core.ui.components.ReorderableWorkoutHistoryList
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.WorkoutChartConfigurationsList
import app.readylytics.health.core.ui.components.WorkoutChartDataMap
import app.readylytics.health.core.ui.components.WorkoutHistoryConfigurationsList
import app.readylytics.health.core.ui.components.WorkoutHistoryDataMap
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun DateSwitcherSection(
    selectedDate: java.time.LocalDate,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateSelected: (java.time.LocalDate) -> Unit,
    earliestDate: java.time.LocalDate?,
    isDisabled: Boolean,
    modifier: Modifier = Modifier,
) {
    DateSwitcher(
        selectedDate = selectedDate,
        onPreviousDay = onPreviousDay,
        onNextDay = onNextDay,
        onDateSelected = onDateSelected,
        earliestDate = earliestDate,
        enabled = !isDisabled,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                .padding(top = MaterialTheme.spacing.pageTop),
    )
}

@Composable
fun CardsDisplaySection(
    uiState: WorkoutsUiState,
    cardDataMap: CardDataMap,
    isManagingCards: Boolean,
    onToggleCardVisibility: (CardId, Boolean) -> Unit,
    onReorderCards: (List<app.readylytics.health.core.model.domain.dashboard.CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardLoader(
        isLoading = uiState.isLoading,
        skeleton = { WorkoutsCardsSkeleton() },
        content = {
            ReorderableCardGrid(
                cardConfigurations = CardConfigurationsList(uiState.cardConfigurations),
                cardDataMap = cardDataMap,
                isEditing = isManagingCards,
                onCardRemove = { cardId -> onToggleCardVisibility(cardId, false) },
                onCardReorder = onReorderCards,
                modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                additionalFullWidthIds = setOf(CardId.RAS_DAILY),
            )
        },
    )
}

@Composable
fun AcwrRangeSection(
    selectedRange: TimeRange,
    isLoading: Boolean,
    isRangeChanging: Boolean,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SectionHeader(
            title = stringResource(R.string.workout_stats_acwr_title),
            enabled = !isLoading,
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
                    enabled = !isLoading && !isRangeChanging,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TimeRange.entries.size),
                    label = { Text(range.label) },
                )
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
    }
}

@Composable
fun ChartsDisplaySection(
    uiState: WorkoutsUiState,
    chartDataMap: WorkoutChartDataMap,
    isManagingCharts: Boolean,
    onToggleChartVisibility: (app.readylytics.health.core.model.domain.workouts.WorkoutChartId, Boolean) -> Unit,
    onReorderCharts: (List<app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration>) -> Unit,
) {
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
                isEditing = isManagingCharts,
                onChartHide = { chartId -> onToggleChartVisibility(chartId, false) },
                onChartReorder = onReorderCharts,
            )
        },
    )
}

@Composable
fun HistoryDisplaySection(
    uiState: WorkoutsUiState,
    historyDataMap: WorkoutHistoryDataMap,
    isManagingHistory: Boolean,
    onToggleHistoryVisibility: (app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId, Boolean) -> Unit,
    onReorderHistory: (List<app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration>) -> Unit,
) {
    CardLoader(
        isLoading = uiState.isLoading,
        skeleton = { WorkoutListSectionSkeleton() },
        content = {
            ReorderableWorkoutHistoryList(
                historyConfigurations = WorkoutHistoryConfigurationsList(uiState.historyConfigurations),
                historyDataMap = historyDataMap,
                isEditing = isManagingHistory,
                onHistoryHide = { historyId -> onToggleHistoryVisibility(historyId, false) },
                onHistoryReorder = onReorderHistory,
            )
        },
    )
}

@Composable
fun StatusAndFooterSection(
    isManagingWorkoutsLayout: Boolean,
    onToggleWorkoutsManagement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        StatusLegend()

        if (!isManagingWorkoutsLayout) {
            CustomizeButton(
                onClick = onToggleWorkoutsManagement,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.pageHorizontal,
                            vertical = MaterialTheme.spacing.pageSectionGap,
                        ),
            )
        }
    }
}

@Composable
fun CustomizeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
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

@Composable
fun WorkoutsCardsSkeleton() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal, vertical = MaterialTheme.spacing.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        ScoreDialSkeleton(height = 156.dp)
        ScoreDialSkeleton(height = 156.dp)
    }
}

@Composable
fun WorkoutListSectionSkeleton() {
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
