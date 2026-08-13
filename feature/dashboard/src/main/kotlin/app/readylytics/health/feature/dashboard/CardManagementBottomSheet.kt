package app.readylytics.health.feature.dashboard

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardManagementBottomSheet(
    cards: List<CardConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode?) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.manage_cards),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.manage_cards),
                    items =
                        cards.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = DashboardCardCatalog.spec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = card.requestedDisplayMode,
                                onVisibilityChanged = { onCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onCardDisplayModeChanged(card.cardId, it) },
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
