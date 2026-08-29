package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Residual-fatigue snapshot computation for the daily scoring pipeline (shadow mode). Mirrors
 * [RasTotalsComputer]/[DailyTrimpComputer]: the repository orchestrates, this class owns the
 * fatigue math. The walk-forward path advances the shared accumulator; the single-day fallback
 * sums over the same fixed window.
 */
class ResidualFatigueComputer(
    private val dataLoader: ScoringDayDataLoader,
    private val computeResidualFatigueUseCase: ComputeResidualFatigueUseCase,
) {
    // 8 * max configured half-life (96h) / 24 = 32 days: captures >99.6% of the decayed signal.
    // Shared by the walk-forward seed and the single-day fallback so both paths cover the same
    // workout window regardless of the user's configured half-life (spec §9 determinism).
    private val seedLookbackDays: Long =
        (8.0 * SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS / 24.0).toLong()

    /**
     * Prefetches the workout-impulse series (keyed by end time, ascending) for a whole
     * `[startDate, endDate]` walk-forward, seeded with [seedLookbackDays] of lookback so early
     * days include decayed contributions from prior workouts.
     */
    suspend fun fetchWalkForwardContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardFatigueContext {
        val fromMs =
            startDate.minusDays(seedLookbackDays)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val toMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val seedCutoffMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return WalkForwardFatigueContext(dataLoader.loadFatigueSeedWorkoutInputs(fromMs, seedCutoffMs, toMs))
    }

    /**
     * Computes the day's residual-fatigue snapshot at next-day midnight. The walk-forward path
     * (non-null [fatigueContext]) advances the shared accumulator; the single-day fallback (null
     * context) sums over the same [seedLookbackDays] window. Returns null when disabled (shadow
     * metric: never feeds Readiness).
     */
    suspend fun compute(
        context: ScoringDayContext,
        fatigueContext: WalkForwardFatigueContext?,
    ): Float? {
        val config =
            ResidualFatigueConfig(
                enabled = context.prefs.residualFatigueEnabled,
                halfLifeHours = context.prefs.residualFatigueHalfLifeHours,
                fatigueGain = context.prefs.residualFatigueGain,
            )
        if (!config.enabled) return null

        val evalMs = context.nextDayMidnightMs
        return if (fatigueContext != null) {
            advanceAccumulator(fatigueContext, evalMs, config)
        } else {
            val fromMs =
                context.targetDate
                    .minusDays(seedLookbackDays)
                    .atStartOfDay(context.zoneId)
                    .toInstant()
                    .toEpochMilli()
            val workouts = dataLoader.loadFatigueWorkoutInputs(fromMs, evalMs)
            computeResidualFatigueUseCase.compute(
                evalMs,
                workouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
                config,
            )
        }
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
