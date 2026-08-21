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
import app.readylytics.health.core.model.domain.layout.ReorderableItem
import app.readylytics.health.core.ui.R

/**
 * Single-column list supporting drag-and-drop reordering, generic over any [ReorderableItem].
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
    val configById: Map<Id, Config> = rememberListConfigMap(items, dataMap.keys)
    val dragController = rememberListDragController(controller, items, dataMap.keys)

    LaunchedEffect(items, dataMap.keys) {
        dragController.syncFromUpstream(extractListUpstreamOrder(items, dataMap.keys))
    }

    val displayableItems: List<Config> = dragController.pendingOrder.mapNotNull { configById[it] }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hideZoneTopPx by remember { mutableStateOf<Float?>(null) }
    val handleBounds = remember { mutableStateMapOf<Id, Rect>() }

    val currentPerformDragEnd by rememberUpdatedState(
        createListDragEndHandler(dragController, configById, onItemHide, onItemReorder),
    )
    val currentHideZoneTopPx by rememberUpdatedState(hideZoneTopPx)

    val listModifier =
        buildListModifier(
            modifier = modifier,
            isEditing = isEditing,
            onRootPositioned = { rootCoords = it },
            handleBounds = handleBounds,
            dragController = dragController,
            onPerformDragEnd = { currentPerformDragEnd() },
            hideZoneTopPx = { currentHideZoneTopPx },
        )

    Column(
        modifier = listModifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        ReorderableListContent(
            displayableItems = displayableItems,
            dataMap = dataMap,
            isEditing = isEditing,
            draggedId = dragController.draggedCardId,
            dragController = dragController,
            rootCoords = rootCoords,
            onHandlePositioned = { id, coords ->
                rootCoords?.let { root -> handleBounds[id] = root.localBoundingBoxOf(coords) }
            },
        )

        if (isEditing) {
            HideDropZone(
                isHovered = dragController.hoveringDeleteZone,
                onPositioned = { top -> hideZoneTopPx = top },
                rootCoords = rootCoords,
            )
        }
    }
}

@Composable
private fun <Id : Any, Config : ReorderableItem<Id>> rememberListConfigMap(
    items: List<Config>,
    validKeys: Set<Id>,
): Map<Id, Config> =
    remember(items, validKeys) {
        items.filter { it.isVisible && validKeys.contains(it.id) }.associateBy { it.id }
    }

@Composable
private fun <Id : Any, Config : ReorderableItem<Id>> rememberListDragController(
    controller: DragController<Id>?,
    items: List<Config>,
    validKeys: Set<Id>,
): DragController<Id> =
    remember {
        controller ?: DragController(extractListUpstreamOrder(items, validKeys))
    }

private fun <Id : Any, Config : ReorderableItem<Id>> extractListUpstreamOrder(
    items: List<Config>,
    validKeys: Set<Id>,
): List<Id> =
    items
        .filter { it.isVisible && validKeys.contains(it.id) }
        .sortedBy { it.position }
        .map { it.id }

private fun <Id : Any, Config : ReorderableItem<Id>> createListDragEndHandler(
    dragController: DragController<Id>,
    configById: Map<Id, Config>,
    onItemHide: (Id) -> Unit,
    onItemReorder: (List<Config>) -> Unit,
): () -> Unit =
    {
        val result = dragController.onDragEnd()
        val dragged = result.draggedId
        if (dragged != null) {
            if (result.delete) {
                onItemHide(dragged)
            } else {
                val updated = result.finalOrder.mapNotNull { id -> configById[id] }
                onItemReorder(updated)
            }
        }
    }

@Composable
private fun <Id : Any> buildListModifier(
    modifier: Modifier,
    isEditing: Boolean,
    onRootPositioned: (LayoutCoordinates) -> Unit,
    handleBounds: Map<Id, Rect>,
    dragController: DragController<Id>,
    onPerformDragEnd: () -> Unit,
    hideZoneTopPx: () -> Float?,
): Modifier {
    val hapticFeedback = LocalHapticFeedback.current
    return modifier
        .onGloballyPositioned(onRootPositioned)
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
                        onDragEnd = onPerformDragEnd,
                        onDragCancel = onPerformDragEnd,
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragController.draggedCardId != null) {
                                dragController.onDrag(dragAmount, hideZoneTopPx())
                            }
                        },
                    )
                }
            } else {
                Modifier
            },
        )
}

@Composable
private fun <Id : Any, Config : ReorderableItem<Id>> ReorderableListContent(
    displayableItems: List<Config>,
    dataMap: Map<Id, @Composable (Config) -> Unit>,
    isEditing: Boolean,
    draggedId: Id?,
    dragController: DragController<Id>,
    rootCoords: LayoutCoordinates?,
    onHandlePositioned: (Id, LayoutCoordinates) -> Unit,
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
                    content = remember(item, dataMap[item.id]) { { dataMap[item.id]!!(item) } },
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
}

@Composable
private fun HideDropZone(
    isHovered: Boolean,
    onPositioned: (Float) -> Unit,
    rootCoords: LayoutCoordinates?,
) {
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .onGloballyPositioned { coords ->
                    rootCoords?.let { root ->
                        onPositioned(root.localBoundingBoxOf(coords).top)
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
