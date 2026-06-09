package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.ui.components.reorder.DragController
import kotlin.math.roundToInt

// Cards that should span entire width instead of pairing into a row.
private val FULL_WIDTH_CARDS = setOf(CardId.STEPS)

/**
 * Grid that supports drag-and-drop reordering of cards.
 *
 * Source of truth for order during a drag is [DragController.pendingOrder]. Upstream
 * `cardConfigurations` is used only to look up the renderable config for each id and
 * to seed/sync the controller when no drag is active.
 *
 * All slot bounds are stored in a single shared coordinate space: the root Column's
 * local space. This is what makes the 2-D hit test in DragController correct.
 */
@Immutable
data class CardConfigurationsList(
    val items: List<CardConfiguration>,
)

@Immutable
data class CardDataMap(
    val map: Map<CardId, @Composable () -> Unit>,
)

@Composable
fun ReorderableCardGrid(
    cardConfigurations: CardConfigurationsList,
    cardDataMap: CardDataMap,
    isEditing: Boolean,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController? = null,
) {
    val items = cardConfigurations.items
    val dataMap = cardDataMap.map

    // Visible + renderable configs, keyed for O(1) lookup at render and drop time.
    val configByCardId: Map<CardId, CardConfiguration> =
        remember(items, dataMap) {
            items
                .filter { it.isVisible && dataMap.containsKey(it.cardId) }
                .associateBy { it.cardId }
        }

    val dragController =
        remember {
            controller ?: DragController(
                items
                    .filter { it.isVisible && dataMap.containsKey(it.cardId) }
                    .sortedBy { it.position }
                    .map { it.cardId },
            )
        }

    // Sync controller from upstream when not actively dragging. Only the filtered + sorted
    // ids enter the controller so pendingOrder always matches what we actually render.
    LaunchedEffect(items) {
        val upstreamOrder =
            items
                .filter { it.isVisible && dataMap.containsKey(it.cardId) }
                .sortedBy { it.position }
                .map { it.cardId }
        dragController.syncFromUpstream(upstreamOrder)
    }

    // Render order is driven by the controller, not by upstream — gives the live drag preview.
    // mapNotNull is defensive against transient mismatches (e.g. a card removed upstream
    // before sync, while we already had it in pendingOrder).
    val displayableCards: List<CardConfiguration> =
        dragController.pendingOrder
            .mapNotNull { configByCardId[it] }

    // Root coordinates for the grid. All slot bounds are recorded relative to this so the
    // DragController operates in a single coordinate space (fixes paired-row vs full-width
    // coordinate-space mismatch).
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var deleteZoneTopPx by remember { mutableStateOf<Float?>(null) }

    val draggedId = dragController.draggedCardId
    val hapticFeedback = LocalHapticFeedback.current

    val performDragEnd = {
        val result = dragController.onDragEnd()
        val draggedId = result.draggedId
        if (draggedId != null) {
            if (result.delete) {
                onCardRemove(draggedId)
            } else {
                val updated =
                    result.finalOrder
                        .mapNotNull { id -> configByCardId[id] }
                        .mapIndexed { index, config -> config.copy(position = index) }
                onCardReorder(updated)
            }
        }
    }

    Column(
        modifier =
            modifier
                .onGloballyPositioned { rootCoords = it }
                .then(
                    if (isEditing) {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val targetCardId =
                                        dragController.slotBounds.entries
                                            .firstOrNull { (_, rect) ->
                                                rect.contains(offset)
                                            }?.key
                                    if (targetCardId != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragController.onDragStart(targetCardId)
                                    }
                                },
                                onDragEnd = { performDragEnd() },
                                onDragCancel = { performDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragController.draggedCardId != null) {
                                        dragController.onDrag(dragAmount, deleteZoneTopPx)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var cardIndex = 0
        while (cardIndex < displayableCards.size) {
            val card = displayableCards[cardIndex]

            if (card.cardId in FULL_WIDTH_CARDS) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (draggedId == card.cardId) 1f else 0f)
                            .onGloballyPositioned { coords ->
                                rootCoords?.let { root ->
                                    dragController.updateSlotBounds(card.cardId, root.localBoundingBoxOf(coords))
                                }
                            },
                ) {
                    RenderCardItem(
                        card = card,
                        cardDataMap = dataMap,
                        isEditing = isEditing,
                        controller = dragController,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                cardIndex++
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val leftCard = displayableCards[cardIndex]
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .zIndex(if (draggedId == leftCard.cardId) 1f else 0f)
                                .onGloballyPositioned { coords ->
                                    rootCoords?.let { root ->
                                        dragController.updateSlotBounds(
                                            leftCard.cardId,
                                            root.localBoundingBoxOf(coords),
                                        )
                                    }
                                },
                    ) {
                        RenderCardItem(
                            card = leftCard,
                            cardDataMap = dataMap,
                            isEditing = isEditing,
                            controller = dragController,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        )
                    }
                    cardIndex++

                    val isHalfWidth =
                        cardIndex < displayableCards.size &&
                            displayableCards[cardIndex].cardId !in FULL_WIDTH_CARDS
                    if (isHalfWidth) {
                        val rightCard = displayableCards[cardIndex]
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .zIndex(if (draggedId == rightCard.cardId) 1f else 0f)
                                    .onGloballyPositioned { coords ->
                                        rootCoords?.let { root ->
                                            dragController.updateSlotBounds(
                                                rightCard.cardId,
                                                root.localBoundingBoxOf(coords),
                                            )
                                        }
                                    },
                        ) {
                            RenderCardItem(
                                card = rightCard,
                                cardDataMap = dataMap,
                                isEditing = isEditing,
                                controller = dragController,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            )
                        }
                        cardIndex++
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Deletion drop zone at the bottom when editing.
        if (isEditing) {
            Spacer(modifier = Modifier.height(16.dp))
            val isHovered = dragController.hoveringDeleteZone
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .onGloballyPositioned { coords ->
                            rootCoords?.let { root ->
                                deleteZoneTopPx = root.localBoundingBoxOf(coords).top
                            }
                        },
                color =
                    if (isHovered) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.action_delete_drop_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderCardItem(
    card: CardConfiguration,
    cardDataMap: Map<CardId, @Composable () -> Unit>,
    isEditing: Boolean,
    controller: DragController,
    modifier: Modifier,
) {
    val isDragged = controller.draggedCardId == card.cardId
    val cardContent = cardDataMap[card.cardId]!!

    val wrappedContent: @Composable () -> Unit =
        if (card.cardId in setOf(CardId.SLEEP_SCORE, CardId.READINESS)) {
            @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth().height(156.dp),
                    contentAlignment = Alignment.Center,
                ) { cardContent() }
            }
        } else {
            cardContent
        }

    ReorderableCardItem(
        card = card,
        content = wrappedContent,
        isEditing = isEditing,
        isDragged = isDragged,
        controller = controller,
        modifier = modifier,
    )
}

@Composable
private fun ReorderableCardItem(
    card: CardConfiguration,
    content: @Composable (() -> Unit)?,
    isEditing: Boolean,
    isDragged: Boolean,
    controller: DragController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .then(
                    if (isDragged) {
                        Modifier
                            .offset {
                                IntOffset(
                                    controller.dragOffset.x.roundToInt(),
                                    controller.dragOffset.y.roundToInt(),
                                )
                            }.graphicsLayer {
                                alpha = 0.9f
                                shadowElevation = 12.dp.toPx()
                                scaleX = 1.05f
                                scaleY = 1.05f
                            }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditing) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = stringResource(R.string.accessibility_drag_to_reorder),
                    modifier = Modifier.padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (content != null) {
                    content()
                }
            }
        }
    }
}
