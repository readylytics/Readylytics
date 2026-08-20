package app.readylytics.health.core.model.domain.workouts

import app.readylytics.health.core.model.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutChartConfiguration(
    val chartId: WorkoutChartId,
    override val isVisible: Boolean,
    override val position: Int,
) : ReorderableItem<WorkoutChartId> {
    override val id: WorkoutChartId get() = chartId
}
