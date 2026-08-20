package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Single source of truth for mapping a raw `WorkoutData.exerciseType` (a Health Connect
 * numeric id such as "56", or a free-text name) onto a [WorkoutLayoutType].
 *
 * `exerciseTypeToDisplayName` delegates here so the display grouping and the layout
 * grouping cannot drift apart.
 */
object WorkoutLayoutTypeMapper {
    private val BY_NUMERIC_ID: Map<String, WorkoutLayoutType> =
        mapOf(
            "56" to WorkoutLayoutType.RUNNING,
            "79" to WorkoutLayoutType.WALKING,
            "8" to WorkoutLayoutType.CYCLING,
            "73" to WorkoutLayoutType.SWIMMING,
            "74" to WorkoutLayoutType.SWIMMING,
            "70" to WorkoutLayoutType.STRENGTH,
            "37" to WorkoutLayoutType.HIKING,
            "83" to WorkoutLayoutType.YOGA,
            "48" to WorkoutLayoutType.PILATES,
            "25" to WorkoutLayoutType.ELLIPTICAL,
            "54" to WorkoutLayoutType.ROWING,
            "68" to WorkoutLayoutType.STAIRS,
            "69" to WorkoutLayoutType.STAIRS,
            "36" to WorkoutLayoutType.HIIT,
        )

    private val BY_NAME: Map<String, WorkoutLayoutType> =
        mapOf(
            "running" to WorkoutLayoutType.RUNNING,
            "walking" to WorkoutLayoutType.WALKING,
            "cycling" to WorkoutLayoutType.CYCLING,
            "swimming" to WorkoutLayoutType.SWIMMING,
            "strength" to WorkoutLayoutType.STRENGTH,
            "hiking" to WorkoutLayoutType.HIKING,
            "yoga" to WorkoutLayoutType.YOGA,
            "pilates" to WorkoutLayoutType.PILATES,
            "elliptical" to WorkoutLayoutType.ELLIPTICAL,
            "rowing" to WorkoutLayoutType.ROWING,
            "stairs" to WorkoutLayoutType.STAIRS,
            "hiit" to WorkoutLayoutType.HIIT,
        )

    fun fromExerciseType(raw: String): WorkoutLayoutType {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return WorkoutLayoutType.OTHER
        BY_NUMERIC_ID[trimmed]?.let { return it }
        val normalized =
            trimmed
                .removePrefix("EXERCISE_TYPE_")
                .replace('_', ' ')
                .trim()
                .lowercase()
        return BY_NAME[normalized] ?: WorkoutLayoutType.OTHER
    }
}
