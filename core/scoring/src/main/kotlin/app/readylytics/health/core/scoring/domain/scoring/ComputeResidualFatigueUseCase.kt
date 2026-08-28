package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import javax.inject.Inject
import kotlin.math.pow

class ComputeResidualFatigueUseCase
    @Inject
    constructor() {
        data class FatigueWorkoutInput(
            val endTimeMs: Long,
            val trimp: Float,
        )

        fun compute(
            evaluationTimeMs: Long,
            workouts: List<FatigueWorkoutInput>,
            config: ResidualFatigueConfig,
        ): Float {
            if (!config.enabled) return 0f
            val halfLifeMs = config.halfLifeHours.toDouble() * 3_600_000.0
            var fatigue = 0.0
            for (w in workouts) {
                if (w.trimp <= 0f || w.endTimeMs > evaluationTimeMs) continue
                val elapsedMs = (evaluationTimeMs - w.endTimeMs).toDouble()
                fatigue += config.fatigueGain * w.trimp * 2.0.pow(-elapsedMs / halfLifeMs)
            }
            return fatigue.toFloat()
        }

        fun advanceAccumulator(
            accumulatedFatigue: Double,
            lastEvalMs: Long,
            currentEvalMs: Long,
            newImpulses: List<FatigueWorkoutInput>,
            config: ResidualFatigueConfig,
        ): Pair<Double, Long> {
            if (!config.enabled) return 0.0 to currentEvalMs
            val halfLifeMs = config.halfLifeHours.toDouble() * 3_600_000.0
            var fatigue =
                if (lastEvalMs == Long.MIN_VALUE) {
                    0.0
                } else {
                    // Clamp: an out-of-order advance (currentEvalMs before lastEvalMs) must decay,
                    // never amplify the accumulated fatigue.
                    val elapsed = (currentEvalMs - lastEvalMs).toDouble().coerceAtLeast(0.0)
                    accumulatedFatigue * 2.0.pow(-elapsed / halfLifeMs)
                }
            for (impulse in newImpulses) {
                if (impulse.trimp <= 0f || impulse.endTimeMs > currentEvalMs) continue
                val elapsed = (currentEvalMs - impulse.endTimeMs).toDouble().coerceAtLeast(0.0)
                fatigue += config.fatigueGain * impulse.trimp * 2.0.pow(-elapsed / halfLifeMs)
            }
            return fatigue to currentEvalMs
        }
    }
