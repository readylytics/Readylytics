package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.util.logE
import java.util.PriorityQueue
import kotlin.math.pow

/**
 * Candidate state produced by [FatigueCursor.previewThrough], committed only after
 * day summary persistence succeeds.
 */
data class FatigueCandidate(
    val fatigue: Float?,
    val advancedFatigue: Double,
    val advancedEvalMs: Long,
    val consumedInputs: List<FatigueWorkoutInput>,
)

/**
 * Ascending fatigue cursor managing accumulated fatigue across monotonically increasing
 * evaluation timestamps.
 *
 * Implements [previewThrough] and [commit] semantics so uncommitted days (failures or
 * unavailable assemblies) leave the cursor state unchanged.
 */
class FatigueCursor(
    seedInputs: List<FatigueWorkoutInput>,
    val seedIncomplete: Boolean = false,
    val config: ResidualFatigueConfig = ResidualFatigueConfig(),
    private val advancer: ((
        accumulatedFatigue: Double,
        lastEvalMs: Long,
        currentEvalMs: Long,
        newImpulses: List<FatigueWorkoutInput>,
    ) -> Pair<Double, Long>)? = null,
) {
    private val comparator = compareBy<FatigueWorkoutInput>({ it.endTimeMs }, { it.workoutId })
    private val pendingInputs = PriorityQueue(comparator)
    private val pendingByWorkoutId = mutableMapOf<String, FatigueWorkoutInput>()
    private val consumedByWorkoutId = mutableMapOf<String, FatigueWorkoutInput>()

    var accumulatedFatigue: Double = 0.0
        private set
    var lastEvaluationTimeMs: Long = Long.MIN_VALUE
        private set
    var lastCandidate: FatigueCandidate? = null
        private set

    init {
        registerCanonicalImpulses(seedInputs)
    }

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

    fun previewThrough(evaluationTimeMs: Long): FatigueCandidate {
        if (seedIncomplete) {
            val candidate =
                FatigueCandidate(
                    fatigue = null,
                    advancedFatigue = accumulatedFatigue,
                    advancedEvalMs = evaluationTimeMs,
                    consumedInputs = emptyList(),
                )
            lastCandidate = candidate
            return candidate
        }

        val eligibleImpulses = mutableListOf<FatigueWorkoutInput>()
        val tempQueue = PriorityQueue(pendingInputs)
        while (tempQueue.peek()?.endTimeMs?.let { it <= evaluationTimeMs } == true) {
            eligibleImpulses += tempQueue.remove()
        }

        val advanceFn = advancer ?: defaultAdvancer(config)
        val (advancedFatigue, advancedEvalMs) =
            advanceFn(
                accumulatedFatigue,
                lastEvaluationTimeMs,
                evaluationTimeMs,
                eligibleImpulses,
            )

        val candidate =
            FatigueCandidate(
                fatigue = advancedFatigue.toFloat(),
                advancedFatigue = advancedFatigue,
                advancedEvalMs = advancedEvalMs,
                consumedInputs = eligibleImpulses,
            )
        lastCandidate = candidate
        return candidate
    }

    fun commit(candidate: FatigueCandidate) {
        if (lastCandidate == candidate) {
            lastCandidate = null
        }
        accumulatedFatigue = candidate.advancedFatigue
        lastEvaluationTimeMs = candidate.advancedEvalMs
        candidate.consumedInputs.forEach { input ->
            pendingInputs.remove(input)
            pendingByWorkoutId.remove(input.workoutId)
            consumedByWorkoutId[input.workoutId] = input
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

    companion object {
        private const val TAG = "FatigueCursor"
        private const val MILLIS_PER_HOUR = 3_600_000.0

        fun defaultAdvancer(
            config: ResidualFatigueConfig,
        ): (Double, Long, Long, List<FatigueWorkoutInput>) -> Pair<Double, Long> {
            val halfLifeMs = config.halfLifeHours.toDouble() * MILLIS_PER_HOUR
            return { acc, last, curr, impulses ->
                if (halfLifeMs <= 0.0) {
                    0.0 to curr
                } else {
                    var fatigue =
                        if (last == Long.MIN_VALUE) {
                            0.0
                        } else {
                            val elapsed = (curr - last).toDouble().coerceAtLeast(0.0)
                            acc * 2.0.pow(-elapsed / halfLifeMs)
                        }
                    for (impulse in impulses) {
                        if (impulse.trimp <= 0f || impulse.endTimeMs > curr) continue
                        val elapsed = (curr - impulse.endTimeMs).toDouble().coerceAtLeast(0.0)
                        fatigue += config.fatigueGain * impulse.trimp * 2.0.pow(-elapsed / halfLifeMs)
                    }
                    fatigue to curr
                }
            }
        }
    }
}
