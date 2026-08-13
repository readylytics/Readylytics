package app.readylytics.health.feature.sleep.overview

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.components.ManagementBottomSheet
import app.readylytics.health.core.ui.components.ManagementItem
import app.readylytics.health.core.ui.components.ManagementSection
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepCardCatalog
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepManagementBottomSheet(
    topCardConfigurations: List<SleepTopCardConfiguration>,
    chartConfigurations: List<SleepChartConfiguration>,
    metricCardConfigurations: List<SleepMetricCardConfiguration>,
    onTopCardVisibilityChanged: (SleepTopCardId, Boolean) -> Unit,
    onChartVisibilityChanged: (SleepChartId, Boolean) -> Unit,
    onMetricCardVisibilityChanged: (SleepMetricCardId, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    onTopCardDisplayModeChanged: ((SleepTopCardId, DashboardCardDisplayMode?) -> Unit)? = null,
    onMetricCardDisplayModeChanged: ((SleepMetricCardId, DashboardCardDisplayMode?) -> Unit)? = null,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.sleep_manage_layout),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.sleep_management_top_cards_section_title),
                    items =
                        topCardConfigurations.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "top_card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = SleepCardCatalog.topCardSpec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = SleepCardCatalog.requestedTopCardMode(card),
                                onVisibilityChanged = { onTopCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onTopCardDisplayModeChanged?.invoke(card.cardId, it) },
                            )
                        },
                ),
                ManagementSection(
                    title = stringResource(R.string.sleep_management_charts_section_title),
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
                    title = stringResource(R.string.sleep_management_metrics_section_title),
                    items =
                        metricCardConfigurations.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "metric_card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = SleepCardCatalog.metricCardSpec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = SleepCardCatalog.requestedMetricCardMode(card),
                                onVisibilityChanged = { onMetricCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onMetricCardDisplayModeChanged?.invoke(card.cardId, it) },
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
