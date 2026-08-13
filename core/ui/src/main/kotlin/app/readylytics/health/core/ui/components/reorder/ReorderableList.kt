package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.layout.ReorderableItem

/**
 * Single-column list supporting drag-and-drop reordering, generic over any [ReorderableItem].
 *
 * Single-column counterpart to [ReorderableGrid]: every item is full-width, one per row, so the
 * paired-row layout branch is absent. Dropping onto the bottom "hide" zone calls [onItemHide]
 * (a reversible visibility toggle) instead of removal.
 */
@Composable
fun <Id : Any, Config : ReorderableItem<Id>> ReorderableList(
    items: List<Config>,
    dataMap: Map<Id, @Composable (Config) -> Unit>,
    isEditing: Boolean,
    onItemReorder: (List<Config>) -> Unit,
    onItemHide: (Id) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<Id>? = null,
) {
    val configById: Map<Id, Config> =
        remember(items) {
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

    LaunchedEffect(items, dataMap.keys) {
        val upstreamOrder =
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .sortedBy { it.position }
                .map { it.id }
        dragController.syncFromUpstream(upstreamOrder)
    }

    val displayableItems: List<Config> =
        dragController.pendingOrder.mapNotNull { configById[it] }

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hideZoneTopPx by remember { mutableStateOf<Float?>(null) }

    val handleBounds = remember { mutableStateMapOf<Id, Rect>() }
    val hapticFeedback = LocalHapticFeedback.current

    val draggedId = dragController.draggedCardId

    val performDragEnd = {
        val result = dragController.onDragEnd()
        val draggedId = result.draggedId
        if (draggedId != null) {
            if (result.delete) {
                onItemHide(draggedId)
            } else {
                val updated = result.finalOrder.mapNotNull { id -> configById[id] }
                onItemReorder(updated)
            }
        }
    }

    val onHandlePositioned: (Id, LayoutCoordinates) -> Unit = { id, coords ->
        rootCoords?.let { root -> handleBounds[id] = root.localBoundingBoxOf(coords) }
    }

    val currentHideZoneTopPx by rememberUpdatedState(hideZoneTopPx)
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
                                        dragController.onDrag(dragAmount, currentHideZoneTopPx)
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
        displayableItems.forEach { item ->
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
                        content = { dataMap[item.id]!!(item) },
                        isEditing = isEditing,
                        isDragged = draggedId == item.id,
                        controller = dragController,
                        onHandlePositioned = onHandlePositioned,
                        fixedHeight = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Hide drop zone at the bottom when editing. Reuses the delete-zone mechanism —
        // dropping here hides the item (reversible), not removes it.
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
                                hideZoneTopPx = root.localBoundingBoxOf(coords).top
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
                        imageVector = Icons.Outlined.VisibilityOff,
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
                        text = stringResource(R.string.action_hide_drop_zone),
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
