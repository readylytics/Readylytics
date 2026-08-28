package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Residual-fatigue snapshot computation for the daily scoring pipeline (shadow mode). Mirrors
 * [RasTotalsComputer]/[DailyTrimpComputer]: the repository orchestrates, this class owns the
 * fatigue math. The walk-forward path advances the shared accumulator; the single-day fallback
 * sums over a per-day lookback query.
 */
class ResidualFatigueComputer(
    private val dataLoader: ScoringDayDataLoader,
    private val computeResidualFatigueUseCase: ComputeResidualFatigueUseCase,
) {
    /**
     * Prefetches the workout-impulse series (keyed by end time, ascending) for a whole
     * `[startDate, endDate]` walk-forward, seeded with a 32-day lookback (8 * 96h max half-life /
     * 24) so early days include decayed contributions from prior workouts.
     */
    suspend fun fetchWalkForwardContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardFatigueContext {
        val seedLookbackDays = 32L
        val fromMs =
            startDate.minusDays(seedLookbackDays)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val toMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return WalkForwardFatigueContext(dataLoader.loadFatigueWorkoutInputs(fromMs, toMs))
    }

    /**
     * Computes the day's residual-fatigue snapshot at next-day midnight. The walk-forward path
     * (non-null [fatigueContext]) advances the shared accumulator; the single-day fallback (null
     * context) sums over a per-day 8-half-life lookback query. Returns null when disabled (shadow
     * metric: never feeds Readiness).
     */
    suspend fun compute(
        context: ScoringDayContext,
        fatigueContext: WalkForwardFatigueContext?,
        prefs: UserPreferences,
    ): Float? {
        val config =
            ResidualFatigueConfig(
                enabled = prefs.residualFatigueEnabled,
                halfLifeHours = prefs.residualFatigueHalfLifeHours,
                fatigueGain = prefs.residualFatigueGain,
            )
        if (!config.enabled) return null

        val evalMs = context.nextDayMidnightMs
        return if (fatigueContext != null) {
            advanceAccumulator(fatigueContext, evalMs, config)
        } else {
            val lookbackMs = (8.0 * config.halfLifeHours * 3_600_000.0).toLong()
            val workouts = dataLoader.loadFatigueWorkoutInputs(evalMs - lookbackMs, evalMs)
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
        if (!config.enabled) return 0f
        val workouts = fatigueContext.workoutsByEndTimeMs
        var cursor = fatigueContext.workoutCursor
        val newImpulses = ArrayList<ComputeResidualFatigueUseCase.FatigueWorkoutInput>()
        while (cursor < workouts.size && workouts[cursor].endTimeMs <= evalMs) {
            newImpulses.add(
                ComputeResidualFatigueUseCase.FatigueWorkoutInput(
                    workouts[cursor].endTimeMs,
                    workouts[cursor].trimp,
                ),
            )
            cursor++
        }
        fatigueContext.workoutCursor = cursor
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
