package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.util.RetentionBounds
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
     *
     * The seed itself is deliberately unbounded below — residual fatigue is exact over all retained
     * history, not a fixed-window approximation. The never-backfilled gate is not: it is clamped to
     * the retention start so it can only ever block on rows the startup self-heal can actually
     * repair. See [retentionStartMs].
     */
    suspend fun fetchWalkForwardContext(
        startDate: LocalDate,
        zoneId: ZoneId,
        prefs: UserPreferences,
    ): WalkForwardFatigueContext {
        val boundaryMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val seedInputs = dataLoader.loadCanonicalFatigueSeed(boundaryMs)
        val unbackfilledCount =
            dataLoader.loadUnbackfilledCountBefore(
                retentionStartMs = retentionStartMs(prefs),
                startBeforeMs = boundaryMs,
            )
        return WalkForwardFatigueContext(
            seedInputs = seedInputs,
            seedIncomplete = unbackfilledCount > 0,
        )
    }

    private fun clampedConfig(prefs: UserPreferences): ResidualFatigueConfig =
        // Coerce rather than require: a stored pref outside the validated range should degrade the
        // day to the nearest valid parameter, never fail the whole recompute.
        ResidualFatigueConfig.clamped(
            enabled = prefs.residualFatigueEnabled,
            halfLifeHours = prefs.residualFatigueHalfLifeHours,
            fatigueGain = prefs.residualFatigueGain,
        )

    /**
     * Lower bound of the never-backfilled gate, shared with `WorkoutTrimpBackfillStatus` and the
     * cleanup worker through [RetentionBounds]. Rows older than this are unreachable by both the
     * recompute-only resync and the self-heal, so counting them could never clear.
     */
    private fun retentionStartMs(prefs: UserPreferences): Long =
        RetentionBounds.resolveHistoricalWindow(prefs).startTimeMs

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
        val config = clampedConfig(context.prefs)
        if (!config.enabled) return null

        val evalMs = context.nextDayMidnightMs
        return when (fatigueContext) {
            null -> computeSingleDayFallback(evalMs, config, context.prefs)
            else -> computeWalkForward(fatigueContext, evalMs, config)
        }
    }

    /**
     * Residual fatigue decayed through [nowMs] instead of [compute]'s persisted next-day-midnight
     * snapshot. Reuses [computeSingleDayFallback] verbatim — reconstructs from every retained
     * canonical impulse through [nowMs], same gating (disabled / unbackfilled-gap) as [compute].
     * Never touches the walk-forward accumulator and is not persisted, so it cannot desync
     * `daily_summaries` or a resync's exact-reconstruction guarantees.
     */
    suspend fun computeLive(
        nowMs: Long,
        prefs: UserPreferences,
    ): Float? {
        val config = clampedConfig(prefs)
        if (!config.enabled) return null
        return computeSingleDayFallback(nowMs, config, prefs)
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
        prefs: UserPreferences,
    ): Float? {
        val unbackfilled =
            dataLoader.loadUnbackfilledCountThrough(
                retentionStartMs = retentionStartMs(prefs),
                evaluationTimeMs = evalMs,
            )
        if (unbackfilled > 0) return null
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
