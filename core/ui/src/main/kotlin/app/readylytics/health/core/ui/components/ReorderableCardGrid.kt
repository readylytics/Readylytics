package app.readylytics.health.core.ui.components

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import kotlin.math.roundToInt

// Cards that should span entire width instead of pairing into a row.
private val FULL_WIDTH_CARDS =
    setOf(
        CardId.STEPS,
        CardId.INSIGHTS,
        CardId.AI_RECOMMENDATION,
    )

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
    val items = cardConfigurations.items
    val dataMap = cardDataMap.map

    // Visible + renderable configs, keyed for O(1) lookup at render and drop time.
    val configByCardId: Map<CardId, CardConfiguration> =
        remember(items, dataMap.keys) {
            items
                .filter { it.isVisible && dataMap.containsKey(it.cardId) }
                .associateBy { it.cardId }
        }

    val dragController =
        remember {
            controller ?: DragController<CardId>(
                items
                    .filter { it.isVisible && dataMap.containsKey(it.cardId) }
                    .sortedBy { it.position }
                    .map { it.cardId },
            )
        }

    // Sync controller from upstream when not actively dragging. Only the filtered + sorted
    // ids enter the controller so pendingOrder always matches what we actually render.
    LaunchedEffect(items, dataMap.keys) {
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

    // Bounds of each card's drag-handle, in root-local space. Used only to decide where a drag
    // may START (so taps/gestures elsewhere on the card body never begin a drag). Gesture
    // detection itself lives on the root Column below - not on the handle - so an in-progress
    // drag survives pendingOrder swaps re-parenting the dragged card under a different
    // leftCard/rightCard/full-width Box template mid-gesture (a per-handle pointerInput node
    // gets torn down by that re-parenting, which cancels the gesture after a single swap).
    val handleBounds = remember { mutableStateMapOf<CardId, Rect>() }
    val hapticFeedback = LocalHapticFeedback.current

    val draggedId = dragController.draggedCardId

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

    val onHandlePositioned: (CardId, LayoutCoordinates) -> Unit = { cardId, coords ->
        rootCoords?.let { root -> handleBounds[cardId] = root.localBoundingBoxOf(coords) }
    }

    // pointerInput(Unit) below never restarts across recomposition, so its closure would
    // otherwise capture stale performDragEnd/deleteZoneTopPx from the composition it first ran
    // in. rememberUpdatedState keeps it reading the current values (see commit 9d7faf1).
    val currentDeleteZoneTopPx by rememberUpdatedState(deleteZoneTopPx)
    val currentPerformDragEnd by rememberUpdatedState(performDragEnd)

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
                                        handleBounds.entries
                                            .firstOrNull { (_, rect) -> rect.contains(offset) }
                                            ?.key
                                    if (targetCardId != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragController.onDragStart(targetCardId)
                                    }
                                },
                                onDragEnd = { currentPerformDragEnd() },
                                onDragCancel = { currentPerformDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragController.draggedCardId != null) {
                                        dragController.onDrag(dragAmount, currentDeleteZoneTopPx)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
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
                        onHandlePositioned = onHandlePositioned,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                cardIndex++
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
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
                            onHandlePositioned = onHandlePositioned,
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
                                onHandlePositioned = onHandlePositioned,
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
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
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
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
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
    cardDataMap: Map<CardId, @Composable (CardConfiguration) -> Unit>,
    isEditing: Boolean,
    controller: DragController<CardId>,
    onHandlePositioned: (CardId, LayoutCoordinates) -> Unit,
    modifier: Modifier,
) {
    // Keying the whole slot by cardId (rather than by loop position) keeps composition
    // identity stable across reorders and mode-only edits, so per-card local state (e.g. the
    // display-mode menu's expanded flag) does not leak between sibling cards.
    key(card.cardId) {
        val isDragged = controller.draggedCardId == card.cardId
        val cardContent = cardDataMap[card.cardId]!!

        val wrappedContent: @Composable () -> Unit =
            if (card.cardId in setOf(CardId.SLEEP_SCORE, CardId.READINESS)) {
                @Composable {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(MaterialTheme.dimens.cardHeight),
                        contentAlignment = Alignment.Center,
                    ) { cardContent(card) }
                }
            } else {
                { cardContent(card) }
            }

        ReorderableCardItem(
            card = card,
            content = wrappedContent,
            isEditing = isEditing,
            isDragged = isDragged,
            controller = controller,
            onHandlePositioned = onHandlePositioned,
            modifier = modifier,
        )
    }
}

@Composable
private fun ReorderableCardItem(
    card: CardConfiguration,
    content: @Composable (() -> Unit)?,
    isEditing: Boolean,
    isDragged: Boolean,
    controller: DragController<CardId>,
    onHandlePositioned: (CardId, LayoutCoordinates) -> Unit,
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
                    .then(
                        if (isEditing) {
                            Modifier.padding(
                                horizontal = MaterialTheme.spacing.small,
                                vertical = MaterialTheme.spacing.extraSmall,
                            )
                        } else {
                            Modifier
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditing) {
                // Drag gesture detection lives on the grid's root Column (survives pendingOrder
                // swaps reparenting this card mid-drag - see handleBounds comment above). This
                // Box only reports its own bounds so the root's hit test can restrict drag START
                // to this 48dp handle, keeping taps/menus elsewhere on the card (e.g. the
                // display-mode selector) from starting a drag.
                //
                // The contentDescription lives on this 48dp Box (not the inner Icon): there is no
                // mergeDescendants/clickable/toggleable anywhere in this handle, so a description
                // on the Icon alone would resolve, in the a11y tree, to the Icon's own leaf node —
                // which lays out at the vector's intrinsic size (~24dp), not this enclosing 48dp
                // touch target. Putting it here means onNodeWithContentDescription (and TalkBack)
                // resolve directly to the actual 48dp target. The Icon itself is decorative under
                // a described parent, so its own contentDescription stays null to avoid a
                // double announcement.
                val dragHandleDescription = stringResource(R.string.accessibility_drag_to_reorder)
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = dragHandleDescription }
                            .onGloballyPositioned { coords -> onHandlePositioned(card.cardId, coords) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (content != null) {
                    content()
                }
            }
        }
    }
}
