package app.readylytics.health.data.preferences

import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutType

/**
 * Proto <-> domain mapping for workout detail layouts. Unknown enum names (written by a
 * newer app version) decode to null and are dropped by the caller rather than throwing,
 * matching the tolerance rule used across the other layout mappers.
 */
object WorkoutDetailLayoutMapper {
    fun toDomain(proto: WorkoutDetailItemConfigurationProto): WorkoutDetailItemConfiguration? {
        val itemId =
            runCatching { WorkoutDetailItemId.valueOf(proto.itemId) }.getOrNull() ?: return null
        return WorkoutDetailItemConfiguration(
            itemId = itemId,
            isVisible = proto.isVisible,
            position = proto.position,
        )
    }

    fun toProto(domain: WorkoutDetailItemConfiguration): WorkoutDetailItemConfigurationProto =
        WorkoutDetailItemConfigurationProto
            .newBuilder()
            .setItemId(domain.itemId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()

    fun typeFromKey(key: String): WorkoutLayoutType? = runCatching { WorkoutLayoutType.valueOf(key) }.getOrNull()

    fun toLayoutProto(items: List<WorkoutDetailItemConfiguration>): WorkoutDetailLayoutProto =
        WorkoutDetailLayoutProto
            .newBuilder()
            .addAllItems(items.map { toProto(it) })
            .build()
}
