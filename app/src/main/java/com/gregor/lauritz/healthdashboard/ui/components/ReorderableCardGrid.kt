package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.background
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

@Composable
fun ReorderableCardGrid(
    cardConfigurations: List<CardConfiguration>,
    cardDataMap: Map<CardId, @Composable () -> Unit>,
    isEditing: Boolean,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(IntOffset.Zero) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }

    val visibleCards = cardConfigurations.filter { it.isVisible }.sortedBy { it.position }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visibleCards.forEachIndexed { index, card ->
            val isDragged = draggedIndex == index
            val isTarget = targetIndex == index && draggedIndex != null

            ReorderableCardItem(
                card = card,
                content = cardDataMap[card.cardId],
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
                    if (draggedIndex != null && targetIndex != null && targetIndex != draggedIndex) {
                        val newCards = visibleCards.toMutableList()
                        if (draggedIndex != null && targetIndex != null) {
                            val draggedCard = newCards.removeAt(draggedIndex!!)
                            newCards.add(targetIndex!!, draggedCard)

                            // Update positions and persist
                            val updated = newCards.mapIndexed { i, config ->
                                config.copy(position = i)
                            }
                            onCardReorder(updated)
                        }
                    }
                    draggedIndex = null
                    dragOffset = IntOffset.Zero
                    targetIndex = null
                },
                onDrag = { x, y ->
                    if (isEditing && draggedIndex != null) {
                        dragOffset += IntOffset(x.roundToInt(), y.roundToInt())

                        // Calculate target index based on drag offset
                        val cardHeight = 120 // approximate card height with spacing
                        val movementThreshold = cardHeight / 2
                        val newTargetIndex = if (dragOffset.y > movementThreshold && targetIndex!! < visibleCards.size - 1) {
                            targetIndex!! + 1
                        } else if (dragOffset.y < -movementThreshold && targetIndex!! > 0) {
                            targetIndex!! - 1
                        } else {
                            targetIndex
                        }

                        if (newTargetIndex != targetIndex) {
                            targetIndex = newTargetIndex
                            dragOffset = IntOffset.Zero
                        }
                    }
                },
                onRemove = { onCardRemove(card.cardId) },
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
    modifier: Modifier = Modifier,
) {
    var currentDragOffset by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
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
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
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

