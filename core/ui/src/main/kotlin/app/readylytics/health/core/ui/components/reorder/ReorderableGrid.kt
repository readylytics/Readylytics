package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.layout.ReorderableItem
import app.readylytics.health.core.ui.R

/**
 * Grid that supports drag-and-drop reordering, generic over any [ReorderableItem].
 */
@Composable
fun <Id : Any, Config : ReorderableItem<Id>> ReorderableGrid(
    items: List<Config>,
    dataMap: Map<Id, @Composable (Config) -> Unit>,
    isEditing: Boolean,
    onItemReorder: (List<Config>) -> Unit,
    onItemDropToRemove: (Id) -> Unit,
    fullWidthIds: Set<Id>,
    modifier: Modifier = Modifier,
    fixedHeightIds: Set<Id> = emptySet(),
    verticalSpacing: Dp = MaterialTheme.spacing.small,
    controller: DragController<Id>? = null,
) {
    val configById: Map<Id, Config> = rememberConfigMap(items, dataMap.keys)
    val dragController = rememberDragController(controller, items, dataMap.keys)

    LaunchedEffect(items, dataMap.keys) {
        dragController.syncFromUpstream(extractUpstreamOrder(items, dataMap.keys))
    }

    val displayableItems: List<Config> = dragController.pendingOrder.mapNotNull { configById[it] }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var deleteZoneTopPx by remember { mutableStateOf<Float?>(null) }
    val handleBounds = remember { mutableStateMapOf<Id, Rect>() }

    val currentPerformDragEnd by rememberUpdatedState(
        createDragEndHandler(dragController, configById, onItemDropToRemove, onItemReorder),
    )
    val currentDeleteZoneTopPx by rememberUpdatedState(deleteZoneTopPx)

    val gridModifier =
        buildGridModifier(
            modifier = modifier,
            isEditing = isEditing,
            onRootPositioned = { rootCoords = it },
            handleBounds = handleBounds,
            dragController = dragController,
            onPerformDragEnd = { currentPerformDragEnd() },
            deleteZoneTopPx = { currentDeleteZoneTopPx },
        )

    val gridContext =
        ReorderableGridContext(
            dataMap = dataMap,
            isEditing = isEditing,
            draggedId = dragController.draggedCardId,
            dragController = dragController,
            rootCoords = rootCoords,
            onHandlePositioned = { id, coords ->
                rootCoords?.let { root -> handleBounds[id] = root.localBoundingBoxOf(coords) }
            },
            fixedHeightIds = fixedHeightIds,
        )

    Column(
        modifier = gridModifier,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        ReorderableGridContent(
            displayableItems = displayableItems,
            fullWidthIds = fullWidthIds,
            context = gridContext,
        )

        if (isEditing) {
            DeleteDropZone(
                isHovered = dragController.hoveringDeleteZone,
                onPositioned = { top -> deleteZoneTopPx = top },
                rootCoords = rootCoords,
            )
        }
    }
}

@Composable
private fun <Id : Any, Config : ReorderableItem<Id>> rememberConfigMap(
    items: List<Config>,
    validKeys: Set<Id>,
): Map<Id, Config> =
    remember(items, validKeys) {
        items.filter { it.isVisible && validKeys.contains(it.id) }.associateBy { it.id }
    }

@Composable
private fun <Id : Any, Config : ReorderableItem<Id>> rememberDragController(
    controller: DragController<Id>?,
    items: List<Config>,
    validKeys: Set<Id>,
): DragController<Id> =
    remember {
        controller ?: DragController(extractUpstreamOrder(items, validKeys))
    }

private fun <Id : Any, Config : ReorderableItem<Id>> extractUpstreamOrder(
    items: List<Config>,
    validKeys: Set<Id>,
): List<Id> =
    items
        .filter { it.isVisible && validKeys.contains(it.id) }
        .sortedBy { it.position }
        .map { it.id }

private fun <Id : Any, Config : ReorderableItem<Id>> createDragEndHandler(
    dragController: DragController<Id>,
    configById: Map<Id, Config>,
    onItemDropToRemove: (Id) -> Unit,
    onItemReorder: (List<Config>) -> Unit,
): () -> Unit =
    {
        val result = dragController.onDragEnd()
        val dragged = result.draggedId
        if (dragged != null) {
            if (result.delete) {
                onItemDropToRemove(dragged)
            } else {
                val updated = result.finalOrder.mapNotNull { id -> configById[id] }
                onItemReorder(updated)
            }
        }
    }

@Composable
private fun <Id : Any> buildGridModifier(
    modifier: Modifier,
    isEditing: Boolean,
    onRootPositioned: (LayoutCoordinates) -> Unit,
    handleBounds: Map<Id, Rect>,
    dragController: DragController<Id>,
    onPerformDragEnd: () -> Unit,
    deleteZoneTopPx: () -> Float?,
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
                                dragController.onDrag(dragAmount, deleteZoneTopPx())
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
private fun <Id : Any, Config : ReorderableItem<Id>> ReorderableGridContent(
    displayableItems: List<Config>,
    fullWidthIds: Set<Id>,
    context: ReorderableGridContext<Id, Config>,
) {
    var index = 0
    while (index < displayableItems.size) {
        val item = displayableItems[index]
        if (item.id in fullWidthIds) {
            FullWidthRow(item = item, context = context)
            index++
        } else {
            val leftItem = displayableItems[index]
            index++
            val isNextHalfWidth = index < displayableItems.size && displayableItems[index].id !in fullWidthIds
            val rightItem = if (isNextHalfWidth) displayableItems[index] else null
            if (isNextHalfWidth) index++

            PairedRow(
                leftItem = leftItem,
                rightItem = rightItem,
                context = context,
            )
        }
    }
}

@Composable
private fun DeleteDropZone(
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
