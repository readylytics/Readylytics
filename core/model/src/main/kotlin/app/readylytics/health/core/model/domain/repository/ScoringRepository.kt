package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import java.time.LocalDate
import java.time.ZoneId

interface ScoringRepository {
    /**
     * Computes and persists the daily summary for [targetDate]. [prefs] null reads a fresh
     * preferences snapshot; a multi-day walk-forward (daily sync / resync) must pass one snapshot
     * shared across every day it recomputes, or a preference change mid-walk-forward silently
     * mixes old- and new-preference days (SCORE-004).
     *
     * PERF-002/WP-20/WP-22/WP-27: a multi-day walk-forward passes [contexts] so the TRIMP series,
     * the RHR/HRV baseline windows, and the residual-fatigue accumulator are fetched once for the
     * run (via [fetchWalkForwardTrimpContext], [fetchWalkForwardBaselineContext] and
     * [fetchWalkForwardFatigueContext]) instead of re-querying their own lookback windows per day.
     * The same [contexts] instance must be handed to every day of the run, oldest day first, so the
     * fatigue accumulator decays and adds impulses in the correct order. The default (all null) is
     * the single-day case.
     */
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate,
        steps: Long? = null,
        prefs: UserPreferences? = null,
        contexts: WalkForwardContexts = WalkForwardContexts(),
    )

    /**
     * PERF-002/WP-20: fetches the workout-only and everyday-HR TRIMP series once, covering the
     * full lookback every day in `[startDate, endDate]` will need, for a caller to hold across a
     * multi-day walk-forward instead of re-querying per day.
     */
    suspend fun fetchWalkForwardTrimpContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardTrimpContext

    /**
     * PERF-002/WP-22: fetches the sleep sessions covering the widest RHR/HRV baseline lookback
     * (56 days, [app.readylytics.health.core.model.domain.scoring.ScoringConstants.HRV_SIGMA_WINDOW_DAYS])
     * every day in `[startDate, endDate]` will need, for a caller to hold across a multi-day
     * walk-forward instead of re-querying per day.
     */
    suspend fun fetchWalkForwardBaselineContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardBaselineContext

    /**
     * PERF-002/WP-27: prefetches historical seed impulses (workouts with startTime before startDate)
     * needed for exact retained-history reconstruction across `[startDate, endDate]`. The caller holds
     * the returned mutable [WalkForwardFatigueContext] across the whole walk-forward and passes it to every
     * [computeAndPersistDailySummary] call in that run.
     */
    suspend fun fetchWalkForwardFatigueContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardFatigueContext

    /**
     * Fetches wearable-reported VO2 Max readings covering the 30-day trailing lookback every day
     * in `[startDate, endDate]` will need, for a caller to hold across a multi-day walk-forward
     * instead of re-querying per day.
     */
    suspend fun fetchWalkForwardVo2MaxContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardVo2MaxContext

    suspend fun computeDailySummary(targetDate: LocalDate): DailySummary

    /**
     * Residual fatigue decayed through [nowMs] rather than the persisted end-of-day snapshot
     * ([DailySummary.residualFatigue]). Non-persisting: does not touch `daily_summaries` or the
     * walk-forward accumulator. For dashboard display of the *current* day only — a finished day's
     * persisted snapshot is already correct and should be used instead.
     *
     * [nowMs] is required rather than defaulted: this codebase injects `java.time.Clock` everywhere,
     * and a `System.currentTimeMillis()` default would let a caller silently bypass the injected
     * clock and become non-deterministic under test.
     */
    suspend fun computeCurrentResidualFatigue(nowMs: Long): Float?

    suspend fun persist(summary: DailySummary)

    suspend fun toReadinessResult(summary: DailySummary): ReadinessResult
}
