package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.core.ui.components.reorder.ReorderableList

@Immutable
data class WorkoutHistoryConfigurationsList(
    val items: List<WorkoutHistoryConfiguration>,
)

@Immutable
data class WorkoutHistoryDataMap(
    val map: Map<WorkoutHistoryId, @Composable (WorkoutHistoryConfiguration) -> Unit>,
)

@Composable
fun ReorderableWorkoutHistoryList(
    historyConfigurations: WorkoutHistoryConfigurationsList,
    historyDataMap: WorkoutHistoryDataMap,
    isEditing: Boolean,
    onHistoryHide: (WorkoutHistoryId) -> Unit,
    onHistoryReorder: (List<WorkoutHistoryConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<WorkoutHistoryId>? = null,
) {
    ReorderableList(
        items = historyConfigurations.items,
        dataMap = historyDataMap.map,
        isEditing = isEditing,
        onItemReorder = onHistoryReorder,
        onItemHide = onHistoryHide,
        modifier = modifier,
        controller = controller,
    )
}
