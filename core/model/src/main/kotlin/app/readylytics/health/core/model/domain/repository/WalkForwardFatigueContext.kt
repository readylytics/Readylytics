package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.util.logE
import java.util.PriorityQueue

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
 * Holds the retained-history seed plus freshly calculated canonical workout impulses from the current
 * walk-forward. Pending impulses are deterministically ordered by end time and stable workout ID.
 *
 * Mutable, so deliberately a plain [class] rather than a data class. Each day advances
 * [accumulatedFatigue] via the decay+add step and [lastEvaluationTimeMs] is the previous day's
 * evaluation timestamp.
 */
class WalkForwardFatigueContext(
    seedInputs: List<FatigueWorkoutInput>,
    /**
     * True when the seed query had to drop workouts inside the fatigue horizon whose canonical
     * `modelTrimp` has never been backfilled. The accumulator cannot reconstruct those impulses, so
     * the day's snapshot is unknown rather than merely low; [ResidualFatigueComputer] persists null.
     *
     * The window is bounded by
     * [app.readylytics.health.core.model.domain.scoring.FatigueHorizon.gateStartMs] so the flag can
     * always clear: rows the startup self-heal cannot reach must not hold the metric hostage.
     */
    val seedIncomplete: Boolean = false,
) {
    private val comparator = compareBy<FatigueWorkoutInput>({ it.endTimeMs }, { it.workoutId })
    private val pendingInputs = PriorityQueue(comparator)
    private val pendingByWorkoutId = mutableMapOf<String, FatigueWorkoutInput>()
    private val consumedByWorkoutId = mutableMapOf<String, FatigueWorkoutInput>()

    var accumulatedFatigue: Double = 0.0
    var lastEvaluationTimeMs: Long = Long.MIN_VALUE

    init {
        registerCanonicalImpulses(seedInputs)
    }

    /**
     * Registers freshly calculated canonical impulses. A workout already consumed by an earlier
     * evaluation keeps its consumed value: the accumulator has already applied that impulse, so
     * re-applying a conflicting one would double count. `WorkoutDao.getWorkoutsInRange` partitions
     * strictly by `startTime`, so each workout is registered by exactly one day and a conflict is
     * unreachable — it is logged rather than thrown so that, if it ever fires, the day is not
     * downgraded to `DAY_SYNC_ERROR` and left with a stale summary.
     */
    fun registerCanonicalImpulses(inputs: List<FatigueWorkoutInput>) {
        inputs.forEach { input ->
            val consumed = consumedByWorkoutId[input.workoutId]
            when {
                consumed == null -> registerPendingInput(input)
                consumed == input -> Unit
                else ->
                    logE(TAG) {
                        "Conflicting canonical fatigue input for ${input.workoutId}: " +
                            "keeping consumed trimp=${consumed.trimp}, ignoring ${input.trimp}"
                    }
            }
        }
    }

    fun takeImpulsesThrough(evaluationTimeMs: Long): List<FatigueWorkoutInput> {
        val inputs = mutableListOf<FatigueWorkoutInput>()
        while (pendingInputs.peek()?.endTimeMs?.let { it <= evaluationTimeMs } == true) {
            val input = pendingInputs.remove()
            pendingByWorkoutId.remove(input.workoutId)
            consumedByWorkoutId[input.workoutId] = input
            inputs += input
        }
        return inputs
    }

    private fun registerPendingInput(input: FatigueWorkoutInput) {
        val previous = pendingByWorkoutId[input.workoutId]
        if (previous == input) return
        if (previous != null) pendingInputs.remove(previous)
        pendingInputs += input
        pendingByWorkoutId[input.workoutId] = input
    }

    private companion object {
        const val TAG = "WalkForwardFatigueContext"
    }
}
