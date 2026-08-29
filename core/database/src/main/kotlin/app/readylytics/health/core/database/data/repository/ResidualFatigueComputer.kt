package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Residual-fatigue snapshot computation for the daily scoring pipeline (shadow mode). Mirrors
 * [RasTotalsComputer]/[DailyTrimpComputer]: the repository orchestrates, this class owns the
 * fatigue math. The walk-forward path advances the shared accumulator; the single-day fallback
 * reconstructs the same state from all retained canonical workout impulses.
 */
class ResidualFatigueComputer(
    private val dataLoader: ScoringDayDataLoader,
    private val computeResidualFatigueUseCase: ComputeResidualFatigueUseCase,
) {
    /**
     * Seeds a walk-forward with every retained canonical workout assigned before its start
     * boundary. Boundary-straddling workouts remain pending until their end timestamp reaches an
     * evaluation point.
     */
    suspend fun fetchWalkForwardContext(
        startDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardFatigueContext {
        val boundaryMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val seedInputs = dataLoader.loadCanonicalFatigueSeed(boundaryMs)
        val unbackfilledCount = dataLoader.loadUnbackfilledCountBefore(boundaryMs)
        return WalkForwardFatigueContext(
            seedInputs = seedInputs,
            seedIncomplete = unbackfilledCount > 0,
        )
    }

    /**
     * Computes the day's residual-fatigue snapshot at next-day midnight. The walk-forward path
     * (non-null [fatigueContext]) advances the shared accumulator; the single-day fallback (null
     * context) reconstructs from every retained canonical impulse through the evaluation. Returns
     * null when disabled (shadow metric: never feeds Readiness) or when the seed dropped a
     * never-backfilled retained workout (unknown, not low — HIGH-2).
     */
    suspend fun compute(
        context: ScoringDayContext,
        fatigueContext: WalkForwardFatigueContext?,
    ): Float? {
        // Coerce rather than require: a stored pref outside the validated range should degrade the
        // day to the nearest valid parameter, never fail the whole recompute.
        val config =
            ResidualFatigueConfig.clamped(
                enabled = context.prefs.residualFatigueEnabled,
                halfLifeHours = context.prefs.residualFatigueHalfLifeHours,
                fatigueGain = context.prefs.residualFatigueGain,
            )
        if (!config.enabled) return null

        val evalMs = context.nextDayMidnightMs
        return when (fatigueContext) {
            null -> computeSingleDayFallback(evalMs, config)
            else -> computeWalkForward(fatigueContext, evalMs, config)
        }
    }

    private fun computeWalkForward(
        fatigueContext: WalkForwardFatigueContext,
        evalMs: Long,
        config: ResidualFatigueConfig,
    ): Float? =
        if (fatigueContext.seedIncomplete) {
            null
        } else {
            advanceAccumulator(fatigueContext, evalMs, config)
        }

    private suspend fun computeSingleDayFallback(
        evalMs: Long,
        config: ResidualFatigueConfig,
    ): Float? {
        if (dataLoader.loadUnbackfilledCountThrough(evalMs) > 0) return null
        val workouts = dataLoader.loadCanonicalFatigueInputsThrough(evalMs)
        return computeResidualFatigueUseCase.compute(
            evalMs,
            workouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
            config,
        )
    }

    /**
     * Advances the shared walk-forward accumulator by one day: decays the accumulated fatigue from
     * the previous evaluation time to [evalMs], adds every new impulse with end time in
     * `(lastEvaluationTimeMs, evalMs]` (single-pass cursor walk), and stores the result back into
     * [fatigueContext]. Delegates to [ComputeResidualFatigueUseCase.advanceAccumulator] so the
     * accumulator and the summation fallback stay one source of truth.
     */
    private fun advanceAccumulator(
        fatigueContext: WalkForwardFatigueContext,
        evalMs: Long,
        config: ResidualFatigueConfig,
    ): Float {
        val newImpulses =
            fatigueContext.takeImpulsesThrough(evalMs).map {
                ComputeResidualFatigueUseCase.FatigueWorkoutInput(
                    it.endTimeMs,
                    it.trimp,
                )
            }
        val (fatigue, advancedEvalMs) =
            computeResidualFatigueUseCase.advanceAccumulator(
                accumulatedFatigue = fatigueContext.accumulatedFatigue,
                lastEvalMs = fatigueContext.lastEvaluationTimeMs,
                currentEvalMs = evalMs,
                newImpulses = newImpulses,
                config = config,
            )
        fatigueContext.accumulatedFatigue = fatigue
        fatigueContext.lastEvaluationTimeMs = advancedEvalMs
        return fatigue.toFloat()
    }
}
