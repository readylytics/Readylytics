package app.readylytics.health.core.model.domain.repository

/**
 * Room-mappable per-workout fatigue impulse. Intentionally distinct from
 * [app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase.FatigueWorkoutInput]:
 * this one is the DAO return type (mapped from `workout_records`), the use-case one is the pure-math
 * input. Do not merge them.
 */
data class FatigueWorkoutInput(
    val endTimeMs: Long,
    val trimp: Float,
)

/**
 * Residual-fatigue state accumulator shared across one walk-forward (daily sync or resync recompute).
 * Holds the full prefetched workout-impulse series (sorted by end time ascending, seeded with a
 * 32-day lookback so early days include decayed contributions from prior workouts) plus the mutable
 * running state advanced once per recomputed day.
 *
 * Mutable, so deliberately a plain [class] rather than a data class. Each day advances
 * [accumulatedFatigue] via the decay+add step, [workoutCursor] tracks the single-pass walk over
 * [workoutsByEndTimeMs], and [lastEvaluationTimeMs] is the previous day's evaluation timestamp.
 */
class WalkForwardFatigueContext(
    val workoutsByEndTimeMs: List<FatigueWorkoutInput>,
) {
    var accumulatedFatigue: Double = 0.0
    var lastEvaluationTimeMs: Long = Long.MIN_VALUE
    var workoutCursor: Int = 0
}
