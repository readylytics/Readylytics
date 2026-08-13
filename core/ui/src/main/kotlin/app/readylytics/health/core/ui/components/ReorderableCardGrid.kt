package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.core.ui.components.reorder.ReorderableGrid
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId

// Cards that should span the entire width instead of pairing into a row.
private val FULL_WIDTH_CARDS =
    setOf(
        CardId.STEPS,
        CardId.INSIGHTS,
        CardId.AI_RECOMMENDATION,
    )

// Gauge dial cards that render inside a fixed-height box so paired rows stay uniform.
private val FIXED_HEIGHT_CARDS =
    setOf(
        CardId.SLEEP_SCORE,
        CardId.READINESS,
    )

@Immutable
data class CardConfigurationsList(
    val items: List<CardConfiguration>,
)

@Immutable
data class CardDataMap(
    val map: Map<CardId, @Composable (CardConfiguration) -> Unit>,
)

@Composable
fun ReorderableCardGrid(
    cardConfigurations: CardConfigurationsList,
    cardDataMap: CardDataMap,
    isEditing: Boolean,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<CardId>? = null,
) {
    ReorderableGrid(
        items = cardConfigurations.items,
        dataMap = cardDataMap.map,
        isEditing = isEditing,
        onItemReorder = onCardReorder,
        onItemDropToRemove = onCardRemove,
        fullWidthIds = FULL_WIDTH_CARDS,
        fixedHeightIds = FIXED_HEIGHT_CARDS,
        modifier = modifier,
        controller = controller,
    )
}
