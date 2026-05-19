package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    fun updateHeight(
        cardId: CardId,
        height: Int,
    ) {
        if (cardHeights[cardId] != height) {
            cardHeights[cardId] = height
        }
    }
}

@Composable
fun rememberReorderableCardState(): ReorderableCardState = remember { ReorderableCardState() }

// Full-width card IDs that should span entire width instead of 50%
private val FULL_WIDTH_CARDS =
    setOf(
        CardId.STEPS,
    )

// Default card height when measurement data is unavailable
private const val DEFAULT_CARD_HEIGHT = 130

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
    val displayableCards =
        remember(cardConfigurations, cardDataMap) {
            cardConfigurations
                .filter { it.isVisible && cardDataMap.containsKey(it.cardId) }
                .sortedBy { it.position }
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var cardIndex = 0
        while (cardIndex < displayableCards.size) {
            val card = displayableCards[cardIndex]

            if (card.cardId in FULL_WIDTH_CARDS) {
                // Full-width card
                renderCardItem(
                    card = card,
                    linearIndex = cardIndex,
                    cardDataMap = cardDataMap,
                    isEditing = isEditing,
                    state = state,
                    displayableCards = displayableCards,
                    onCardRemove = onCardRemove,
                    onCardReorder = onCardReorder,
                    modifier = Modifier.fillMaxWidth(),
                )
                cardIndex++
            } else {
                // Try to pair with next card
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (cardIndex < displayableCards.size) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            renderCardItem(
                                card = displayableCards[cardIndex],
                                linearIndex = cardIndex,
                                cardDataMap = cardDataMap,
                                isEditing = isEditing,
                                state = state,
                                displayableCards = displayableCards,
                                onCardRemove = onCardRemove,
                                onCardReorder = onCardReorder,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            )
                        }
                        cardIndex++
                    }

                    if (cardIndex < displayableCards.size && displayableCards[cardIndex].cardId !in FULL_WIDTH_CARDS) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            renderCardItem(
                                card = displayableCards[cardIndex],
                                linearIndex = cardIndex,
                                cardDataMap = cardDataMap,
                                isEditing = isEditing,
                                state = state,
                                displayableCards = displayableCards,
                                onCardRemove = onCardRemove,
                                onCardReorder = onCardReorder,
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

        // Deletion Drop Zone at the bottom when editing
        if (isEditing) {
            Spacer(modifier = Modifier.height(16.dp))
            val isHovered = state.targetIndex == displayableCards.size
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                color =
                    if (isHovered) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                shape = RoundedCornerShape(16.dp),
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
                        text = "Drop here to remove",
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
private fun renderCardItem(
    card: CardConfiguration,
    linearIndex: Int,
    cardDataMap: Map<CardId, @Composable () -> Unit>,
    isEditing: Boolean,
    state: ReorderableCardState,
    displayableCards: List<CardConfiguration>,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier,
) {
    val isDragged = state.draggedIndex == linearIndex
    val isTarget = state.targetIndex == linearIndex && state.draggedIndex != null
    val cardContent = cardDataMap[card.cardId]!!

    val wrappedContent: @Composable () -> Unit =
        if (card.cardId in setOf(CardId.SLEEP_SCORE, CardId.READINESS)) {
            @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    cardContent()
                }
            }
        } else {
            cardContent
        }

    ReorderableCardItem(
        card = card,
        content = wrappedContent,
        isEditing = isEditing,
        isDragged = isDragged,
        isTarget = isTarget,
        onDragStart = {
            if (isEditing) {
                state.onDragStart(linearIndex)
            }
        },
        onDragEnd = {
            val draggedIdx = state.draggedIndex
            val targetIdx = state.targetIndex
            if (draggedIdx != null && targetIdx != null) {
                if (targetIdx == displayableCards.size) {
                    // Dropped in delete zone
                    onCardRemove(displayableCards[draggedIdx].cardId)
                } else if (targetIdx != draggedIdx) {
                    val newCards = displayableCards.toMutableList()
                    val draggedCard = newCards.removeAt(draggedIdx)
                    newCards.add(targetIdx, draggedCard)

                    val updated =
                        newCards.mapIndexed { i, config ->
                            config.copy(position = i)
                        }
                    onCardReorder(updated)
                }
            }
            state.onDragEnd()
        },
        onDrag = { x, y ->
            if (isEditing && state.draggedIndex != null && state.targetIndex != null) {
                state.dragOffset += IntOffset(x.roundToInt(), y.roundToInt())

                val currentTarget = state.targetIndex!!

                val currentCard = displayableCards.getOrNull(currentTarget)
                val prevCard = displayableCards.getOrNull(currentTarget - 1)
                val nextCard = displayableCards.getOrNull(currentTarget + 1)

                val currentHeight = currentCard?.let { state.cardHeights[it.cardId] } ?: DEFAULT_CARD_HEIGHT
                val prevHeight = prevCard?.let { state.cardHeights[it.cardId] } ?: DEFAULT_CARD_HEIGHT
                val nextHeight = nextCard?.let { state.cardHeights[it.cardId] } ?: DEFAULT_CARD_HEIGHT

                val downThreshold = (currentHeight + nextHeight) / 8f
                val upThreshold = (currentHeight + prevHeight) / 8f

                val maxIndex = displayableCards.size
                val newTargetIndex =
                    if (state.dragOffset.y > downThreshold && currentTarget < maxIndex) {
                        currentTarget + 1
                    } else if (state.dragOffset.y < -upThreshold && currentTarget > 0) {
                        currentTarget - 1
                    } else {
                        currentTarget
                    }

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
        modifier = modifier,
    )
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
        modifier =
            modifier
                .onSizeChanged { size ->
                    onHeightChanged(size.height)
                }.then(
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
                    },
                ).then(
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
                                    currentDragOffset +=
                                        IntOffset(
                                            dragAmount.x.roundToInt(),
                                            dragAmount.y.roundToInt(),
                                        )
                                    onDrag(dragAmount.x, dragAmount.y)
                                },
                            )
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
                    contentDescription = "Drag to reorder",
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
