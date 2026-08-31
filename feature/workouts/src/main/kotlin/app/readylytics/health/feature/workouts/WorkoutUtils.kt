package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutTypeMapper

/**
 * Display name for a raw Health Connect exercise type. Grouped types delegate to
 * [WorkoutLayoutTypeMapper] so this cannot drift from the per-type layout grouping;
 * ungrouped types keep the previous free-text formatting.
 */
fun exerciseTypeToDisplayName(type: String): String =
    when (val layoutType = WorkoutLayoutTypeMapper.fromExerciseType(type)) {
        WorkoutLayoutType.OTHER ->
            type
                .replace("EXERCISE_TYPE_", "")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
                .replace("_", " ")
        WorkoutLayoutType.HIIT -> "HIIT"
        else ->
            layoutType.name
                .lowercase()
                .replaceFirstChar { it.uppercase() }
    }
