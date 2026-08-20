package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.core.ui.components.reorder.ReorderableList

@Immutable
data class WorkoutChartConfigurationsList(
    val items: List<WorkoutChartConfiguration>,
)

@Immutable
data class WorkoutChartDataMap(
    val map: Map<WorkoutChartId, @Composable (WorkoutChartConfiguration) -> Unit>,
)

@Composable
fun ReorderableWorkoutChartList(
    chartConfigurations: WorkoutChartConfigurationsList,
    chartDataMap: WorkoutChartDataMap,
    isEditing: Boolean,
    onChartHide: (WorkoutChartId) -> Unit,
    onChartReorder: (List<WorkoutChartConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<WorkoutChartId>? = null,
) {
    ReorderableList(
        items = chartConfigurations.items,
        dataMap = chartDataMap.map,
        isEditing = isEditing,
        onItemReorder = onChartReorder,
        onItemHide = onChartHide,
        modifier = modifier,
        controller = controller,
    )
}
