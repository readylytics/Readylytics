package app.readylytics.health.core.model.domain.workouts

import app.readylytics.health.core.model.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutHistoryConfiguration(
    val historyId: WorkoutHistoryId,
    override val isVisible: Boolean,
    override val position: Int,
) : ReorderableItem<WorkoutHistoryId> {
    override val id: WorkoutHistoryId get() = historyId
}
