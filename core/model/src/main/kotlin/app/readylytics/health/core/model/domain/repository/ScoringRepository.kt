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
     */
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate,
        steps: Long? = null,
        prefs: UserPreferences? = null,
    )

    /**
     * PERF-002/WP-20/WP-22: same as the 3-arg overload, but reads/writes the TRIMP series through
     * [trimpContext] and the RHR/HRV baseline windows through [baselineContext] instead of
     * independently re-querying their own lookback windows per day. Callers with a multi-day
     * walk-forward must fetch one [trimpContext] (via [fetchWalkForwardTrimpContext]) and one
     * [baselineContext] (via [fetchWalkForwardBaselineContext]) and share both across every day
     * recomputed in that run.
     */
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate,
        steps: Long?,
        prefs: UserPreferences,
        trimpContext: WalkForwardTrimpContext,
        baselineContext: WalkForwardBaselineContext,
    )

    /**
     * PERF-002/WP-20/WP-22 + residual-fatigue walk-forward: same as the 5-arg overload, but also
     * reads/advances the shared [WalkForwardFatigueContext] state accumulator. A multi-day
     * walk-forward must fetch one [fatigueContext] (via [fetchWalkForwardFatigueContext]) and pass it
     * to every day recomputed in that run, oldest day first, so the accumulator decays and adds
     * impulses in the correct order.
     */
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate,
        steps: Long?,
        prefs: UserPreferences,
        trimpContext: WalkForwardTrimpContext,
        baselineContext: WalkForwardBaselineContext,
        fatigueContext: WalkForwardFatigueContext,
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
     * PERF-002/WP-27: prefetches the workout-impulse series (keyed by end time, ascending) needed by
     * every day in `[startDate, endDate]`, seeded with a 32-day lookback so early days include
     * decayed contributions from prior workouts. The caller holds the returned mutable
     * [WalkForwardFatigueContext] across the whole walk-forward and passes it to every
     * [computeAndPersistDailySummary] call in that run.
     */
    suspend fun fetchWalkForwardFatigueContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardFatigueContext

    suspend fun computeDailySummary(targetDate: LocalDate): DailySummary

    suspend fun persist(summary: DailySummary)

    suspend fun toReadinessResult(summary: DailySummary): ReadinessResult
}
