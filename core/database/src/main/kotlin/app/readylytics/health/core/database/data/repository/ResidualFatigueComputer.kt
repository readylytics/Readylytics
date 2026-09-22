package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Residual-fatigue snapshot computation for the daily scoring pipeline. Mirrors
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
     * the run's captured retention start so it can only ever block on rows the startup self-heal
     * can actually repair.
     */
    suspend fun fetchWalkForwardContext(
        startDate: LocalDate,
        zoneId: ZoneId,
        retentionStartMs: Long,
    ): WalkForwardFatigueContext {
        val boundaryMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val seedInputs = dataLoader.loadCanonicalFatigueSeed(boundaryMs)
        val unbackfilledCount =
            dataLoader.loadUnbackfilledCountBefore(
                retentionStartMs = retentionStartMs,
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
            halfLifeHours = prefs.residualFatigueHalfLifeHours,
            fatigueGain = prefs.residualFatigueGain,
        )

    /**
     * Computes the day's residual-fatigue snapshot at next-day midnight. The walk-forward path
     * (non-null [fatigueContext]) advances the shared accumulator; the single-day fallback (null
     * context) reconstructs from every retained canonical impulse through the evaluation. Returns
     * null when the seed dropped a never-backfilled retained workout (unknown, not low — HIGH-2).
     */
    suspend fun compute(
        context: ScoringDayContext,
        fatigueContext: WalkForwardFatigueContext?,
        stagedFatigueInputs: List<FatigueWorkoutInput> = emptyList(),
        stagedWorkouts: List<WorkoutRecordEntity> = emptyList(),
    ): Float? {
        val config = clampedConfig(context.prefs)
        val evalMs = context.nextDayMidnightMs
        return when (fatigueContext) {
            null ->
                computeSingleDayFallback(
                    evalMs = evalMs,
                    config = config,
                    prefs = context.prefs,
                    retentionStartMs = context.runContext.retentionStartMs,
                    stagedFatigueInputs = stagedFatigueInputs,
                    stagedWorkouts = stagedWorkouts,
                )
            else -> computeWalkForward(fatigueContext, evalMs, config)
        }
    }

    /**
     * Residual fatigue decayed through [evaluationTimeMs] instead of [compute]'s persisted
     * next-day-midnight snapshot. Reuses [computeSingleDayFallback] verbatim — reconstructs from
     * every retained canonical impulse through [evaluationTimeMs], with the same unbackfilled-gap
     * gate as [compute], so a never-backfilled retained workout yields null (unknown) rather than a
     * silently low number.
     *
     * Never touches the walk-forward accumulator and is not persisted, so it cannot desync
     * `daily_summaries` or a resync's exact-reconstruction guarantees, and it can be called at any
     * instant — including a past morning's wake time during a historical replay — without moving
     * the shared day-end accumulator backwards.
     */
    suspend fun computeAt(
        evaluationTimeMs: Long,
        prefs: UserPreferences,
        retentionStartMs: Long,
    ): Float? = computeSingleDayFallback(evaluationTimeMs, clampedConfig(prefs), retentionStartMs)

    /** [computeAt] at the current instant, for the live dashboard card. */
    suspend fun computeLive(
        nowMs: Long,
        prefs: UserPreferences,
    ): Float? {
        val runContext = ScoringRunContext.capture(prefs, Instant.ofEpochMilli(nowMs))
        return computeAt(nowMs, prefs, runContext.retentionStartMs)
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
        retentionStartMs: Long,
        stagedFatigueInputs: List<FatigueWorkoutInput> = emptyList(),
        stagedWorkouts: List<WorkoutRecordEntity> = emptyList(),
    ): Float? {
        val unbackfilledInDb =
            dataLoader.loadUnbackfilledCountThrough(
                retentionStartMs = retentionStartMs,
                evaluationTimeMs = evalMs,
            )
        val stagedUnbackfilledInDb =
            stagedWorkouts.count {
                it.modelTrimp == null && it.startTime >= retentionStartMs && it.endTime <= evalMs
            }
        val unbackfilled = (unbackfilledInDb - stagedUnbackfilledInDb).coerceAtLeast(0)
        if (unbackfilled > 0) return null

        val stagedIds = stagedFatigueInputs.map { it.workoutId }.toSet()
        val workoutsFromDb =
            dataLoader.loadCanonicalFatigueInputsThrough(evalMs).filterNot { it.workoutId in stagedIds }
        val allWorkouts =
            (workoutsFromDb + stagedFatigueInputs.filter { it.trimp > 0 })
                .sortedWith(compareBy({ it.endTimeMs }, { it.workoutId }))

        return computeResidualFatigueUseCase.compute(
            evalMs,
            allWorkouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
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
