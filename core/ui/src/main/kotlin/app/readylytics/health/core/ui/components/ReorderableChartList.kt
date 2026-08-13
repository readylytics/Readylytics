package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.core.ui.components.reorder.ReorderableList
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId

@Immutable
data class ChartConfigurationsList(
    val items: List<VitalsChartConfiguration>,
)

@Immutable
data class ChartDataMap(
    val map: Map<VitalsChartId, @Composable (VitalsChartConfiguration) -> Unit>,
)

@Composable
fun ReorderableChartList(
    chartConfigurations: ChartConfigurationsList,
    chartDataMap: ChartDataMap,
    isEditing: Boolean,
    onChartHide: (VitalsChartId) -> Unit,
    onChartReorder: (List<VitalsChartConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<VitalsChartId>? = null,
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
