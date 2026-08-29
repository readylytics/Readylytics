package app.readylytics.health.core.model.domain.repository

/**
 * Room-mappable per-workout fatigue impulse. Intentionally distinct from
 * [app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase.FatigueWorkoutInput]:
 * this one is the DAO return type (mapped from `workout_records`), the use-case one is the pure-math
 * input. Do not merge them.
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
) {
    private val comparator = compareBy<FatigueWorkoutInput>({ it.endTimeMs }, { it.workoutId })
    private val pendingInputs = java.util.PriorityQueue(comparator)
    private val pendingByWorkoutId = mutableMapOf<String, FatigueWorkoutInput>()
    private val consumedByWorkoutId = mutableMapOf<String, FatigueWorkoutInput>()

    var accumulatedFatigue: Double = 0.0
    var lastEvaluationTimeMs: Long = Long.MIN_VALUE

    init {
        registerCanonicalImpulses(seedInputs)
    }

    fun registerCanonicalImpulses(inputs: List<FatigueWorkoutInput>) {
        inputs.forEach { input ->
            val consumed = consumedByWorkoutId[input.workoutId]
            when {
                consumed != null && consumed == input -> Unit
                consumed != null ->
                    throw IllegalArgumentException("Conflicting canonical fatigue input for ${input.workoutId}")
                else -> registerPendingInput(input)
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
}
