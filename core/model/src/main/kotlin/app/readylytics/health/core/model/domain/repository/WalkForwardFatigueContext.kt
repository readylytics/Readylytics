package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig

/**
 * Room-mappable per-workout fatigue impulse. Intentionally distinct from
 * `ComputeResidualFatigueUseCase.FatigueWorkoutInput` in the `core:scoring` module (not linkable
 * from here — `core:model` does not depend on `core:scoring`): this one is the DAO return type
 * (mapped from `workout_records`), the use-case one is the pure-math input. Do not merge them.
 */
data class FatigueWorkoutInput(
    val workoutId: String,
    val endTimeMs: Long,
    val trimp: Float,
)

/**
 * Residual-fatigue state accumulator shared across one walk-forward (daily sync or resync recompute).
 * Holds two independent ascending cursors:
 * - [dayEndCursor]: advances along day-end boundaries (midnight), persisting the daily summary snapshot.
 * - [morningCursor]: advances along wake timestamps, providing exact morning recovery fatigue without
 *   future workout leakage from later that day.
 *
 * Pending impulses are deterministically ordered by end time and stable workout ID.
 */
class WalkForwardFatigueContext(
    seedInputs: List<FatigueWorkoutInput>,
    val seedIncomplete: Boolean = false,
    config: ResidualFatigueConfig = ResidualFatigueConfig(),
    advancer: ((
        accumulatedFatigue: Double,
        lastEvalMs: Long,
        currentEvalMs: Long,
        newImpulses: List<FatigueWorkoutInput>,
    ) -> Pair<Double, Long>)? = null,
) {
    val dayEndCursor = FatigueCursor(seedInputs, seedIncomplete, config, advancer)
    val morningCursor = FatigueCursor(seedInputs, seedIncomplete, config, advancer)

    val accumulatedFatigue: Double
        get() = dayEndCursor.accumulatedFatigue

    val lastEvaluationTimeMs: Long
        get() = dayEndCursor.lastEvaluationTimeMs

    fun registerCanonicalImpulses(inputs: List<FatigueWorkoutInput>) {
        dayEndCursor.registerCanonicalImpulses(inputs)
        morningCursor.registerCanonicalImpulses(inputs)
    }

    fun takeImpulsesThrough(evaluationTimeMs: Long): List<FatigueWorkoutInput> =
        dayEndCursor.takeImpulsesThrough(evaluationTimeMs)
}
