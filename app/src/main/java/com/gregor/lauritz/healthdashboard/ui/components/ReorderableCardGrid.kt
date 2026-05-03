package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import kotlin.math.roundToInt

// State holder for reorderable card grid
@Stable
class ReorderableCardState {
    // Tracks which card is currently being dragged
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    // Accumulates drag offset for visual feedback during drag
    var dragOffset by mutableStateOf(IntOffset.Zero)
        internal set

    // Tracks which card position would be the drop target
    var targetIndex by mutableStateOf<Int?>(null)
        internal set

    // Store measured heights of cards for accurate drag-and-drop calculations
    // Using SnapshotStateMap to avoid allocations on updates and trigger precise recompositions
    val cardHeights: SnapshotStateMap<CardId, Int> = mutableStateMapOf()

    fun onDragStart(index: Int) {
        draggedIndex = index
        dragOffset = IntOffset.Zero
        targetIndex = index
    }

    fun onDragEnd() {
        draggedIndex = null
        dragOffset = IntOffset.Zero
        targetIndex = null
    }

    fun updateHeight(cardId: CardId, height: Int) {
        if (cardHeights[cardId] != height) {
            cardHeights[cardId] = height
        }
    }
}

@Composable
fun rememberReorderableCardState(): ReorderableCardState {
    return remember { ReorderableCardState() }
}

// Grid component that supports drag-and-drop reordering of cards
// Only visible cards (isVisible=true) are rendered and can be reordered
// Provides visual feedback during drag (alpha, scale, elevation changes)
@Composable
fun ReorderableCardGrid(
    cardConfigurations: List<CardConfiguration>,
    cardDataMap: Map<CardId, @Composable () -> Unit>,
    isEditing: Boolean,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    state: ReorderableCardState = rememberReorderableCardState(),
) {
    // Filter to show only visible cards that have content, sorted by their position
    val displayableCards = remember(cardConfigurations, cardDataMap) {
        cardConfigurations
            .filter { it.isVisible && cardDataMap.containsKey(it.cardId) }
            .sortedBy { it.position }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        displayableCards.forEachIndexed { index, card ->
            val isDragged = state.draggedIndex == index
            val isTarget = state.targetIndex == index && state.draggedIndex != null
            val cardContent = cardDataMap[card.cardId]!!

            ReorderableCardItem(
                card = card,
                content = cardContent,
                isEditing = isEditing,
                isDragged = isDragged,
                isTarget = isTarget,
                onDragStart = {
                    if (isEditing) {
                        state.onDragStart(index)
                    }
                },
                onDragEnd = {
                    // Only process reorder if card was dragged to a different position
                    val draggedIdx = state.draggedIndex
                    val targetIdx = state.targetIndex
                    if (draggedIdx != null && targetIdx != null && targetIdx != draggedIdx) {
                        val newCards = displayableCards.toMutableList()
                        val draggedCard = newCards.removeAt(draggedIdx)
                        newCards.add(targetIdx, draggedCard)

                        // Update positions to reflect new order and persist to DataStore
                        val updated = newCards.mapIndexed { i, config ->
                            config.copy(position = i)
                        }
                        onCardReorder(updated)
                    }
                    state.onDragEnd()
                },
                onDrag = { x, y ->
                    if (isEditing && state.draggedIndex != null && state.targetIndex != null) {
                        state.dragOffset += IntOffset(x.roundToInt(), y.roundToInt())

                        val currentTarget = state.targetIndex!!
                        val draggedCard = displayableCards.getOrNull(currentTarget)
                        // Use measured card height, or default to 130 (including spacing) if not yet measured
                        val cardHeight = draggedCard?.let { state.cardHeights[it.cardId] } ?: 130
                        // Require 50% of card height movement to trigger swap to provide steady feel
                        val movementThreshold = cardHeight / 2

                        val newTargetIndex = if (state.dragOffset.y > movementThreshold && currentTarget < displayableCards.size - 1) {
                            currentTarget + 1
                        } else if (state.dragOffset.y < -movementThreshold && currentTarget > 0) {
                            currentTarget - 1
                        } else {
                            currentTarget
                        }

                        // Reset offset when target changes to restart threshold detection
                        if (newTargetIndex != state.targetIndex) {
                            state.targetIndex = newTargetIndex
                            state.dragOffset = IntOffset.Zero
                        }
                    }
                },
                onRemove = { onCardRemove(card.cardId) },
                onHeightChanged = { height ->
                    state.updateHeight(card.cardId, height)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReorderableCardItem(
    card: CardConfiguration,
    content: @Composable (() -> Unit)?,
    isEditing: Boolean,
    isDragged: Boolean,
    isTarget: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onRemove: () -> Unit,
    onHeightChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var currentDragOffset by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                onHeightChanged(size.height)
            }
            .then(
                if (isDragged) {
                    Modifier
                        .offset { currentDragOffset }
                        .graphicsLayer {
                            alpha = 0.75f
                            shadowElevation = 16.dp.toPx()
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                } else if (isTarget) {
                    Modifier
                        .graphicsLayer {
                            alpha = 0.6f
                        }
                } else {
                    Modifier
                }
            )
            .then(
                if (isEditing) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                currentDragOffset = IntOffset.Zero
                                onDragStart()
                            },
                            onDragEnd = {
                                onDragEnd()
                                currentDragOffset = IntOffset.Zero
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentDragOffset += IntOffset(
                                    dragAmount.x.roundToInt(),
                                    dragAmount.y.roundToInt()
                                )
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditing) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier.padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (content != null) {
                    content()
                }
            }

            if (isEditing) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove card",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
