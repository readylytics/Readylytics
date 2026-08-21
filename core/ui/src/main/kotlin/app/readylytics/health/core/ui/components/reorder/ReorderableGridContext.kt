package app.readylytics.health.core.ui.components.reorder

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.LayoutCoordinates
import app.readylytics.health.core.model.domain.layout.ReorderableItem

internal class ReorderableGridContext<Id : Any, Config : ReorderableItem<Id>>(
    val dataMap: Map<Id, @Composable (Config) -> Unit>,
    val isEditing: Boolean,
    val draggedId: Id?,
    val dragController: DragController<Id>,
    val rootCoords: LayoutCoordinates?,
    val onHandlePositioned: (Id, LayoutCoordinates) -> Unit,
    val fixedHeightIds: Set<Id>,
)
