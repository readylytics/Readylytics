package app.readylytics.health.core.model.domain.scoring

enum class WorkoutHrQuality {
    RAW,
    WARM_APPROXIMATE,
    VALIDATED_PRIOR,
    UNAVAILABLE,
}

data class CanonicalWorkoutResult(
    val workoutId: String,
    val endTimeMs: Long,
    val trimp: Float?,
    val quality: WorkoutHrQuality,
    val sourceRevision: Long,
    val scoringSnapshotId: String,
    val algorithmRevision: Int,
)

data class WorkoutScoringIdentity(
    val sourceRevision: Long,
    val scoringSnapshotId: String,
    val algorithmRevision: Int,
)
