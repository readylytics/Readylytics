package app.readylytics.health.feature.workouts

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.components.ManagementBottomSheet
import app.readylytics.health.core.ui.components.ManagementItem
import app.readylytics.health.core.ui.components.ManagementSection
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.domain.workouts.WorkoutChartId
import app.readylytics.health.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.domain.workouts.WorkoutHistoryId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsManagementBottomSheet(
    cardConfigurations: List<CardConfiguration>,
    chartConfigurations: List<WorkoutChartConfiguration>,
    historyConfigurations: List<WorkoutHistoryConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onChartVisibilityChanged: (WorkoutChartId, Boolean) -> Unit,
    onHistoryVisibilityChanged: (WorkoutHistoryId, Boolean) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.workouts_manage_layout),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.workouts_management_cards_section_title),
                    items =
                        cardConfigurations.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = DashboardCardCatalog.spec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = DashboardCardCatalog.requestedMode(card),
                                onVisibilityChanged = { onCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onCardDisplayModeChanged(card.cardId, it) },
                            )
                        },
                ),
                ManagementSection(
                    title = stringResource(R.string.workouts_management_diagrams_section_title),
                    items =
                        chartConfigurations.sortedBy { it.position }.map { chart ->
                            ManagementItem(
                                key = "chart_${chart.chartId.name}",
                                label = stringResource(chart.chartId.displayNameResId),
                                isVisible = chart.isVisible,
                                supportedModes = emptyList(),
                                requestedMode = DashboardCardDisplayMode.VALUE,
                                onVisibilityChanged = { onChartVisibilityChanged(chart.chartId, it) },
                                onDisplayModeChanged = {},
                            )
                        },
                ),
                ManagementSection(
                    title = stringResource(R.string.workouts_management_history_section_title),
                    items =
                        historyConfigurations.sortedBy { it.position }.map { history ->
                            ManagementItem(
                                key = "history_${history.historyId.name}",
                                label = stringResource(history.historyId.displayNameResId),
                                isVisible = history.isVisible,
                                supportedModes = emptyList(),
                                requestedMode = DashboardCardDisplayMode.VALUE,
                                onVisibilityChanged = { onHistoryVisibilityChanged(history.historyId, it) },
                                onDisplayModeChanged = {},
                            )
                        },
                ),
            ),
        onResetToDefaults = onResetToDefaults,
        onDismiss = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    )
}
