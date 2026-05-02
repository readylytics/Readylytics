package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import kotlin.math.roundToInt

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
) {
    // Tracks which card is currently being dragged
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    // Accumulates drag offset for visual feedback during drag
    var dragOffset by remember { mutableStateOf(IntOffset.Zero) }
    // Tracks which card position would be the drop target
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    // Store measured heights of cards for accurate drag-and-drop calculations
    var cardHeights by remember { mutableStateOf<Map<CardId, Int>>(emptyMap()) }

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
            val isDragged = draggedIndex == index
            val isTarget = targetIndex == index && draggedIndex != null
            val cardContent = cardDataMap[card.cardId]!!

            ReorderableCardItem(
                card = card,
                content = cardContent,
                isEditing = isEditing,
                isDragged = isDragged,
                isTarget = isTarget,
                onDragStart = {
                    if (isEditing) {
                        draggedIndex = index
                        dragOffset = IntOffset.Zero
                        targetIndex = index
                    }
                },
                onDragEnd = {
                    // Only process reorder if card was dragged to a different position
                    // Both draggedIndex and targetIndex must be non-null and different
                    if (draggedIndex != null && targetIndex != null && targetIndex != draggedIndex) {
                        val newCards = displayableCards.toMutableList()
                        // Safe to use !! here because outer if guarantees non-null values
                        val draggedCard = newCards.removeAt(draggedIndex!!)
                        newCards.add(targetIndex!!, draggedCard)

                        // Update positions to reflect new order and persist to DataStore
                        val updated = newCards.mapIndexed { i, config ->
                            config.copy(position = i)
                        }
                        onCardReorder(updated)
                    }
                    // Reset drag state after drop is complete
                    draggedIndex = null
                    dragOffset = IntOffset.Zero
                    targetIndex = null
                },
                onDrag = { x, y ->
                    if (isEditing && draggedIndex != null && targetIndex != null) {
                        dragOffset += IntOffset(x.roundToInt(), y.roundToInt())

                        val currentTarget = targetIndex ?: return@onDrag
                        val draggedCard = displayableCards.getOrNull(currentTarget)
                        // Use measured card height, or default to 130 (including spacing) if not yet measured
                        val cardHeight = draggedCard?.let { cardHeights[it.cardId] } ?: 130
                        // Require 50% of card height movement to trigger swap to provide steady feel
                        val movementThreshold = cardHeight / 2

                        val newTargetIndex = if (dragOffset.y > movementThreshold && currentTarget < displayableCards.size - 1) {
                            currentTarget + 1
                        } else if (dragOffset.y < -movementThreshold && currentTarget > 0) {
                            currentTarget - 1
                        } else {
                            currentTarget
                        }

                        // Reset offset when target changes to restart threshold detection
                        if (newTargetIndex != targetIndex) {
                            targetIndex = newTargetIndex
                            dragOffset = IntOffset.Zero
                        }
                    }
                },
                onRemove = { onCardRemove(card.cardId) },
                onSizeChanged = { height ->
                    cardHeights = cardHeights.toMutableMap().apply {
                        this[card.cardId] = height
                    }
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
    onSizeChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var currentDragOffset by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                onSizeChanged(size.height)
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

