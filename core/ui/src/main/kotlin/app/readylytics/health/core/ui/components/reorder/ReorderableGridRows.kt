package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.layout.ReorderableItem

@Composable
internal fun <Id : Any, Config : ReorderableItem<Id>> FullWidthRow(
    item: Config,
    context: ReorderableGridContext<Id, Config>,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .zIndex(if (context.draggedId == item.id) 1f else 0f)
                .onGloballyPositioned { coords ->
                    context.rootCoords?.let { root ->
                        context.dragController.updateSlotBounds(item.id, root.localBoundingBoxOf(coords))
                    }
                },
    ) {
        key(item.id) {
            ReorderableSlot(
                id = item.id,
                content = remember(item, context.dataMap[item.id]) { { context.dataMap[item.id]!!(item) } },
                isEditing = context.isEditing,
                isDragged = context.draggedId == item.id,
                controller = context.dragController,
                onHandlePositioned = context.onHandlePositioned,
                fixedHeight = item.id in context.fixedHeightIds,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun <Id : Any, Config : ReorderableItem<Id>> PairedRow(
    leftItem: Config,
    rightItem: Config?,
    context: ReorderableGridContext<Id, Config>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        PairedHalfSlot(item = leftItem, context = context)
        if (rightItem != null) {
            PairedHalfSlot(item = rightItem, context = context)
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun <Id : Any, Config : ReorderableItem<Id>> RowScope.PairedHalfSlot(
    item: Config,
    context: ReorderableGridContext<Id, Config>,
) {
    val isDragged = context.draggedId == item.id
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .zIndex(if (isDragged) 1f else 0f)
                .onGloballyPositioned { coords ->
                    context.rootCoords?.let { root ->
                        context.dragController.updateSlotBounds(
                            item.id,
                            root.localBoundingBoxOf(coords),
                        )
                    }
                },
    ) {
        key(item.id) {
            ReorderableSlot(
                id = item.id,
                content =
                    remember(
                        item,
                        context.dataMap[item.id],
                    ) { { context.dataMap[item.id]!!(item) } },
                isEditing = context.isEditing,
                isDragged = isDragged,
                controller = context.dragController,
                onHandlePositioned = context.onHandlePositioned,
                fixedHeight = item.id in context.fixedHeightIds,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}
