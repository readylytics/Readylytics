package app.readylytics.health.core.ui.components.reorder

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.layout.ReorderableItem
import kotlin.math.roundToInt

/**
 * Grid that supports drag-and-drop reordering, generic over any [ReorderableItem].
 *
 * Source of truth for order during a drag is [DragController.pendingOrder]. Upstream [items]
 * is used only to look up the renderable config for each id and to seed/sync the controller
 * when no drag is active. Slot bounds live in a single shared coordinate space (the root
 * Column's local space), drag START is gated by the 48dp drag-handle bounds, and a bottom
 * delete drop-zone reuses the DragController delete mechanism (drop = onItemDropToRemove).
 *
 * Layout: items whose id is in [fullWidthIds] render full-width alone; the remaining items
 * pair into rows of two (a lone non-full-width item renders half-width with a spacer), which
 * reproduces both the dashboard gauge pairing and the sleep gauge pairing. Items in
 * [fixedHeightIds] render inside a fixed `dimens.cardHeight` box.
 */
@Composable
fun <Id : Any, Config : ReorderableItem<Id>> ReorderableGrid(
    items: List<Config>,
    dataMap: Map<Id, @Composable (Config) -> Unit>,
    isEditing: Boolean,
    onItemReorder: (List<Config>) -> Unit,
    onItemDropToRemove: (Id) -> Unit,
    fullWidthIds: Set<Id>,
    fixedHeightIds: Set<Id> = emptySet(),
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = MaterialTheme.spacing.small,
    controller: DragController<Id>? = null,
) {
    val configById: Map<Id, Config> =
        remember(items, dataMap.keys) {
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .associateBy { it.id }
        }

    val dragController =
        remember {
            controller ?: DragController(
                items
                    .filter { it.isVisible && dataMap.containsKey(it.id) }
                    .sortedBy { it.position }
                    .map { it.id },
            )
        }

    // Sync controller from upstream when not actively dragging. Only the filtered + sorted
    // ids enter the controller so pendingOrder always matches what we actually render.
    LaunchedEffect(items, dataMap.keys) {
        val upstreamOrder =
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .sortedBy { it.position }
                .map { it.id }
        dragController.syncFromUpstream(upstreamOrder)
    }

    // Render order is driven by the controller, not by upstream — gives the live drag preview.
    val displayableItems: List<Config> =
        dragController.pendingOrder.mapNotNull { configById[it] }

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var deleteZoneTopPx by remember { mutableStateOf<Float?>(null) }

    val handleBounds = remember { mutableStateMapOf<Id, Rect>() }
    val hapticFeedback = LocalHapticFeedback.current

    val draggedId = dragController.draggedCardId

    val performDragEnd = {
        val result = dragController.onDragEnd()
        val draggedId = result.draggedId
        if (draggedId != null) {
            if (result.delete) {
                onItemDropToRemove(draggedId)
            } else {
                val updated = result.finalOrder.mapNotNull { id -> configById[id] }
                onItemReorder(updated)
            }
        }
    }

    val onHandlePositioned: (Id, LayoutCoordinates) -> Unit = { id, coords ->
        rootCoords?.let { root -> handleBounds[id] = root.localBoundingBoxOf(coords) }
    }

    // pointerInput(Unit) below never restarts across recomposition, so its closure would
    // otherwise capture stale performDragEnd/deleteZoneTopPx from the composition it first ran
    // in. rememberUpdatedState keeps it reading the current values.
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
                                    val targetId =
                                        handleBounds.entries
                                            .firstOrNull { (_, rect) -> rect.contains(offset) }
                                            ?.key
                                    if (targetId != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragController.onDragStart(targetId)
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
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        var index = 0
        while (index < displayableItems.size) {
            val item = displayableItems[index]

            if (item.id in fullWidthIds) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (draggedId == item.id) 1f else 0f)
                            .onGloballyPositioned { coords ->
                                rootCoords?.let { root ->
                                    dragController.updateSlotBounds(item.id, root.localBoundingBoxOf(coords))
                                }
                            },
                ) {
                    key(item.id) {
                        ReorderableSlot(
                            id = item.id,
                            content = remember(item, dataMap[item.id]) { { dataMap[item.id]!!(item) } },
                            isEditing = isEditing,
                            isDragged = draggedId == item.id,
                            controller = dragController,
                            onHandlePositioned = onHandlePositioned,
                            fixedHeight = item.id in fixedHeightIds,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                index++
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    val leftItem = displayableItems[index]
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .zIndex(if (draggedId == leftItem.id) 1f else 0f)
                                .onGloballyPositioned { coords ->
                                    rootCoords?.let { root ->
                                        dragController.updateSlotBounds(
                                            leftItem.id,
                                            root.localBoundingBoxOf(coords),
                                        )
                                    }
                                },
                    ) {
                        key(leftItem.id) {
                            ReorderableSlot(
                                id = leftItem.id,
                                content =
                                    remember(
                                        leftItem,
                                        dataMap[leftItem.id],
                                    ) { { dataMap[leftItem.id]!!(leftItem) } },
                                isEditing = isEditing,
                                isDragged = draggedId == leftItem.id,
                                controller = dragController,
                                onHandlePositioned = onHandlePositioned,
                                fixedHeight = leftItem.id in fixedHeightIds,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            )
                        }
                    }
                    index++

                    val isHalfWidth =
                        index < displayableItems.size &&
                            displayableItems[index].id !in fullWidthIds
                    if (isHalfWidth) {
                        val rightItem = displayableItems[index]
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .zIndex(if (draggedId == rightItem.id) 1f else 0f)
                                    .onGloballyPositioned { coords ->
                                        rootCoords?.let { root ->
                                            dragController.updateSlotBounds(
                                                rightItem.id,
                                                root.localBoundingBoxOf(coords),
                                            )
                                        }
                                    },
                        ) {
                            key(rightItem.id) {
                                ReorderableSlot(
                                    id = rightItem.id,
                                    content =
                                        remember(
                                            rightItem,
                                            dataMap[rightItem.id],
                                        ) { { dataMap[rightItem.id]!!(rightItem) } },
                                    isEditing = isEditing,
                                    isDragged = draggedId == rightItem.id,
                                    controller = dragController,
                                    onHandlePositioned = onHandlePositioned,
                                    fixedHeight = rightItem.id in fixedHeightIds,
                                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                                )
                            }
                        }
                        index++
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Delete drop zone at the bottom when editing.
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

/**
 * Single drag-and-drop render slot: drag visuals on the dragged item, an optional 48dp drag
 * handle (gating drag START) and the item content. Shared by [ReorderableGrid] and
 * [ReorderableList]. Keying the slot by id at the call site keeps composition identity stable.
 */
@Composable
internal fun <Id : Any> ReorderableSlot(
    id: Id,
    content: @Composable () -> Unit,
    isEditing: Boolean,
    isDragged: Boolean,
    controller: DragController<Id>,
    onHandlePositioned: (Id, LayoutCoordinates) -> Unit,
    fixedHeight: Boolean,
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
                // Drag gesture detection lives on the root Column (survives pendingOrder swaps
                // reparenting this slot mid-drag). This Box only reports its own bounds so the
                // root's hit test can restrict drag START to this 48dp handle.
                val dragHandleDescription = stringResource(R.string.accessibility_drag_to_reorder)
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = dragHandleDescription }
                            .onGloballyPositioned { coords -> onHandlePositioned(id, coords) },
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
                if (fixedHeight) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(MaterialTheme.dimens.cardHeight),
                        contentAlignment = Alignment.Center,
                    ) { content() }
                } else {
                    content()
                }
            }
        }
    }
}
