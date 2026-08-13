package app.readylytics.health.feature.dashboard

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
import app.readylytics.health.core.ui.components.DisplayModeDropdownSelector
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardManagementBottomSheet(
    cards: List<CardConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
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
                    text = stringResource(R.string.manage_cards),
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

            val sortedCards = remember(cards) { cards.sortedBy { it.position } }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(
                    items = sortedCards,
                    key = { it.cardId.name },
                ) { card ->
                    CardManagementItem(
                        card = card,
                        onVisibilityChanged = { visible ->
                            onCardVisibilityChanged(card.cardId, visible)
                        },
                        onDisplayModeChanged = { mode ->
                            onCardDisplayModeChanged(card.cardId, mode)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = MaterialTheme.spacing.extraSmall),
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
                        selectedMode = card.requestedDisplayMode,
                        supportedModes = spec.supportedModes,
                        onModeSelected = { mode -> if (mode != null) onDisplayModeChanged(mode) },
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
        modifier = modifier,
    )
}
