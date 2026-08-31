package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import kotlin.math.roundToInt

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
                DragHandle(
                    id = id,
                    onHandlePositioned = onHandlePositioned,
                )
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

@Composable
private fun <Id : Any> DragHandle(
    id: Id,
    onHandlePositioned: (Id, LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragHandleDescription = stringResource(R.string.accessibility_drag_to_reorder)
    Box(
        modifier =
            modifier
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
