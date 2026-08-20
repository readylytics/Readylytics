package app.readylytics.health.domain.workouts.detail

import app.readylytics.health.core.model.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutDetailItemConfiguration(
    val itemId: WorkoutDetailItemId,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
) : ReorderableItem<WorkoutDetailItemId> {
    override val id: WorkoutDetailItemId get() = itemId
}
