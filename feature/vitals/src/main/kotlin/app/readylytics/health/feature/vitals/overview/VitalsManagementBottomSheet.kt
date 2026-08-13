package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.core.ui.R as CoreUiR

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
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
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
                            bottom = MaterialTheme.spacing.pageSectionGap,
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

            val sortedCards = remember(cardConfigurations) { cardConfigurations.sortedBy { it.position } }
            val sortedCharts = remember(chartConfigurations) { chartConfigurations.sortedBy { it.position } }

            LazyColumn {
                item {
                    Text(
                        text = stringResource(R.string.vitals_management_cards_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = MaterialTheme.spacing.pageHorizontal,
                                    end = MaterialTheme.spacing.pageHorizontal,
                                    bottom = MaterialTheme.spacing.extraSmall,
                                ),
                    )
                }
                items(
                    items = sortedCards,
                    key = { "card_${it.cardId.name}" },
                ) { card ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(card.cardId.displayNameResId),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = card.isVisible,
                                onCheckedChange = { visible ->
                                    onCardVisibilityChanged(card.cardId, visible)
                                },
                            )
                        },
                        modifier =
                            Modifier.padding(
                                vertical = MaterialTheme.spacing.extraSmall,
                            ),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.vitals_management_diagrams_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = MaterialTheme.spacing.pageHorizontal,
                                    end = MaterialTheme.spacing.pageHorizontal,
                                    top = MaterialTheme.spacing.pageSectionGap,
                                    bottom = MaterialTheme.spacing.extraSmall,
                                ),
                    )
                }
                items(
                    items = sortedCharts,
                    key = { "chart_${it.chartId.name}" },
                ) { chart ->
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
                                onCheckedChange = { visible ->
                                    onChartVisibilityChanged(chart.chartId, visible)
                                },
                            )
                        },
                        modifier =
                            Modifier.padding(
                                vertical = MaterialTheme.spacing.extraSmall,
                            ),
                    )
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
