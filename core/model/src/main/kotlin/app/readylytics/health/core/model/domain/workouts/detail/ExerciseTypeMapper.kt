package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Resolves a raw `WorkoutData.exerciseType` string onto an [ExerciseType].
 *
 * Ingestion stores the Health Connect numeric id (`"2"` for badminton), so the numeric lookup is
 * the production path. The name lookup covers symbolic values (`"EXERCISE_TYPE_BADMINTON"`) and
 * the loose free-text names older callers and fixtures use. Anything unrecognised resolves to
 * [ExerciseType.OTHER_WORKOUT], which is Health Connect's own catch-all type (id 0).
 */
object ExerciseTypeMapper {
    private val BY_ID: Map<Int, ExerciseType> = ExerciseType.entries.associateBy { it.hcId }

    private val BY_NAME: Map<String, ExerciseType> =
        buildMap {
            ExerciseType.entries.forEach { type ->
                put(normalize(type.name), type)
                putIfAbsent(normalize(type.canonicalName), type)
            }
            // Loose names kept for compatibility with callers that never spoke Health Connect's
            // vocabulary; they must keep resolving to the same workout layout as before.
            putIfAbsent("cycling", ExerciseType.BIKING)
            putIfAbsent("bike", ExerciseType.BIKING)
            putIfAbsent("strength", ExerciseType.STRENGTH_TRAINING)
            putIfAbsent("hiit", ExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING)
            putIfAbsent("stairs", ExerciseType.STAIR_CLIMBING)
            putIfAbsent("swimming", ExerciseType.SWIMMING_POOL)
            putIfAbsent("swim", ExerciseType.SWIMMING_POOL)
            putIfAbsent("run", ExerciseType.RUNNING)
            putIfAbsent("treadmill", ExerciseType.RUNNING_TREADMILL)
            putIfAbsent("walk", ExerciseType.WALKING)
            putIfAbsent("hike", ExerciseType.HIKING)
        }

    fun fromRaw(raw: String): ExerciseType {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ExerciseType.OTHER_WORKOUT
        trimmed.toIntOrNull()?.let { id -> return BY_ID[id] ?: ExerciseType.OTHER_WORKOUT }
        return BY_NAME[normalize(trimmed)] ?: ExerciseType.OTHER_WORKOUT
    }

    private fun normalize(value: String): String =
        value
            .trim()
            .lowercase()
            .removePrefix("exercise_type_")
            .replace('_', ' ')
            .trim()
}
