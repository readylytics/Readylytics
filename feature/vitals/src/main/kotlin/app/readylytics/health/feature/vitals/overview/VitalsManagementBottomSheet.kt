package app.readylytics.health.feature.vitals.overview

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.DisplayModeDropdownSelector
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * Unified bottom sheet for customizing the layout of the Vitals tab.
 *
 * Visibility toggles and reset-to-defaults for the two sections (cards and trend charts) are
 * organized into tabs, mirroring the Sleep management sheet. Reordering happens on the screen
 * via drag-and-drop while in edit mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsManagementBottomSheet(
    cardConfigurations: List<CardConfiguration>,
    chartConfigurations: List<VitalsChartConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onChartVisibilityChanged: (VitalsChartId, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
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
                    text = stringResource(R.string.vitals_manage_layout),
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
                    text = { Text(stringResource(R.string.vitals_management_cards_section_title)) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text(stringResource(R.string.vitals_management_diagrams_section_title)) },
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

            val sortedCards = remember(cardConfigurations) { cardConfigurations.sortedBy { it.position } }
            val sortedCharts = remember(chartConfigurations) { chartConfigurations.sortedBy { it.position } }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        items(sortedCards, key = { "card_${it.cardId.name}" }) { card ->
                            CardManagementItem(
                                card = card,
                                onVisibilityChanged = { visible ->
                                    onCardVisibilityChanged(card.cardId, visible)
                                },
                                onDisplayModeChanged = { mode ->
                                    onCardDisplayModeChanged(card.cardId, mode)
                                },
                            )
                        }
                    }
                    1 -> {
                        items(sortedCharts, key = { "chart_${it.chartId.name}" }) { chart ->
                            ChartManagementItem(
                                chart = chart,
                                onVisibilityChanged = { visible ->
                                    onChartVisibilityChanged(chart.chartId, visible)
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
private fun CardManagementItem(
    card: CardConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    onDisplayModeChanged: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = DashboardCardCatalog.spec(card.cardId)
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(card.cardId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent =
            if (spec != null && spec.supportedModes.size > 1) {
                {
                    DisplayModeDropdownSelector(
                        selectedMode = DashboardCardCatalog.requestedMode(card),
                        supportedModes = spec.supportedModes,
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
    chart: VitalsChartConfiguration,
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
