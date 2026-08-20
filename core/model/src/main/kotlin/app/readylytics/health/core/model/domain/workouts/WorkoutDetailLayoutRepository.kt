package app.readylytics.health.core.model.domain.workouts

import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutType
import kotlinx.coroutines.flow.Flow

/**
 * Per-workout-type layout for the workout detail screen. Storage is sparse: a type
 * only gets an entry once the user saves a customization for it, and every other type
 * resolves to the shared defaults.
 */
interface WorkoutDetailLayoutRepository {
    /** Stored layout for [type], merged with defaults; defaults when nothing is stored. */
    fun layoutFor(type: WorkoutLayoutType): Flow<List<WorkoutDetailItemConfiguration>>

    /** Only the types that have a stored customization. Used by backup. */
    fun allLayouts(): Flow<Map<WorkoutLayoutType, List<WorkoutDetailItemConfiguration>>>

    suspend fun updateLayout(
        type: WorkoutLayoutType,
        items: List<WorkoutDetailItemConfiguration>,
    )

    /** Replaces every stored layout. Used by restore — not a merge. */
    suspend fun replaceAll(layouts: Map<WorkoutLayoutType, List<WorkoutDetailItemConfiguration>>)

    /** Clears every stored layout, returning all types to defaults. */
    suspend fun resetAll()
}
