package app.readylytics.health.feature.sleep.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * Unified bottom sheet for customizing the layout of the Sleep tab.
 *
 * Reordering happens on the Sleep screen via drag-and-drop while in edit mode; this sheet
 * provides visibility toggles, display-mode pickers, and reset-to-defaults for the three
 * sections (top cards, charts, metric cards).
 */
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        var selectedTabIndex by remember { mutableIntStateOf(0) }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.spacing.pageSectionGap),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.pageHorizontal,
                            end = MaterialTheme.spacing.pageHorizontal,
                            bottom = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sleep_manage_layout),
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onResetToDefaults) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = stringResource(CoreUiR.string.action_reset_to_defaults),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text(stringResource(R.string.sleep_management_top_cards_section_title)) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text(stringResource(R.string.sleep_management_charts_section_title)) },
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text(stringResource(R.string.sleep_management_metrics_section_title)) },
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

            val sortedTopCards = remember(topCardConfigurations) { topCardConfigurations.sortedBy { it.position } }
            val sortedCharts = remember(chartConfigurations) { chartConfigurations.sortedBy { it.position } }
            val sortedMetricCards =
                remember(metricCardConfigurations) { metricCardConfigurations.sortedBy { it.position } }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        items(sortedTopCards, key = { "top_card_${it.cardId.name}" }) { card ->
                            TopCardManagementItem(
                                card = card,
                                onVisibilityChanged = { visible -> onTopCardVisibilityChanged(card.cardId, visible) },
                                onDisplayModeChanged =
                                    onTopCardDisplayModeChanged?.let { callback ->
                                        { mode -> callback(card.cardId, mode) }
                                    },
                            )
                        }
                    }
                    1 -> {
                        items(sortedCharts, key = { "chart_${it.chartId.name}" }) { chart ->
                            ChartManagementItem(
                                chart = chart,
                                onVisibilityChanged = { visible -> onChartVisibilityChanged(chart.chartId, visible) },
                            )
                        }
                    }
                    2 -> {
                        items(sortedMetricCards, key = { "metric_card_${it.cardId.name}" }) { card ->
                            MetricCardManagementItem(
                                card = card,
                                onVisibilityChanged = { visible ->
                                    onMetricCardVisibilityChanged(card.cardId, visible)
                                },
                                onDisplayModeChanged =
                                    onMetricCardDisplayModeChanged?.let { callback ->
                                        { mode -> callback(card.cardId, mode) }
                                    },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(
                            end = MaterialTheme.spacing.pageHorizontal,
                            top = MaterialTheme.spacing.pageSectionGap,
                        ),
            ) {
                Text(stringResource(CoreUiR.string.action_done))
            }
        }
    }
}

@Composable
private fun TopCardManagementItem(
    card: SleepTopCardConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    onDisplayModeChanged: ((DashboardCardDisplayMode?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(card.cardId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent =
            if (onDisplayModeChanged != null) {
                {
                    DisplayModeDropdownSelector(
                        selectedMode = card.requestedDisplayMode,
                        onModeSelected = onDisplayModeChanged,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            Checkbox(
                checked = card.isVisible,
                onCheckedChange = onVisibilityChanged,
            )
        },
        modifier = modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}

@Composable
private fun ChartManagementItem(
    chart: SleepChartConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(chart.chartId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Checkbox(
                checked = chart.isVisible,
                onCheckedChange = onVisibilityChanged,
            )
        },
        modifier = modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}

@Composable
private fun MetricCardManagementItem(
    card: SleepMetricCardConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    onDisplayModeChanged: ((DashboardCardDisplayMode?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(card.cardId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent =
            if (onDisplayModeChanged != null) {
                {
                    DisplayModeDropdownSelector(
                        selectedMode = card.requestedDisplayMode,
                        onModeSelected = onDisplayModeChanged,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            Checkbox(
                checked = card.isVisible,
                onCheckedChange = onVisibilityChanged,
            )
        },
        modifier = modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayModeDropdownSelector(
    selectedMode: DashboardCardDisplayMode?,
    onModeSelected: (DashboardCardDisplayMode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val displayValue =
        when (selectedMode) {
            DashboardCardDisplayMode.GAUGE -> stringResource(R.string.sleep_management_display_mode_gauge)
            DashboardCardDisplayMode.BAR -> stringResource(R.string.sleep_management_display_mode_bar)
            DashboardCardDisplayMode.VALUE -> stringResource(R.string.sleep_management_display_mode_value)
            null -> stringResource(R.string.sleep_management_display_mode_default)
        }

    val modeOptions =
        listOf(
            null,
            DashboardCardDisplayMode.GAUGE,
            DashboardCardDisplayMode.BAR,
            DashboardCardDisplayMode.VALUE,
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.padding(top = MaterialTheme.spacing.extraSmall),
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sleep_management_display_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modeOptions.forEach { option ->
                val label =
                    when (option) {
                        DashboardCardDisplayMode.GAUGE -> stringResource(R.string.sleep_management_display_mode_gauge)
                        DashboardCardDisplayMode.BAR -> stringResource(R.string.sleep_management_display_mode_bar)
                        DashboardCardDisplayMode.VALUE -> stringResource(R.string.sleep_management_display_mode_value)
                        null -> stringResource(R.string.sleep_management_display_mode_default)
                    }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
