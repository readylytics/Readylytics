package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Maps a raw `WorkoutData.exerciseType` (a Health Connect numeric id such as "56", or a free-text
 * name) onto a [WorkoutLayoutType].
 *
 * The id/name table lives in [ExerciseType]; this delegates so the per-type display label and the
 * layout grouping are derived from a single source and cannot drift apart.
 */
object WorkoutLayoutTypeMapper {
    fun fromExerciseType(raw: String): WorkoutLayoutType = ExerciseTypeMapper.fromRaw(raw).layoutType
}
