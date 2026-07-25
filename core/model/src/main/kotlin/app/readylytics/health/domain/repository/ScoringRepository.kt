package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.preferences.UserPreferences
import java.time.LocalDate
import java.time.ZoneId

interface ScoringRepository {
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate = LocalDate.now(),
        steps: Long? = null,
    )

    /**
     * Same as the no-[prefs] overload, but uses the caller's already-taken preferences snapshot
     * instead of reading a fresh one. A multi-day walk-forward (daily sync / resync) must call
     * this with one snapshot shared across every day it recomputes, or a preference change
     * mid-walk-forward silently mixes old- and new-preference days (SCORE-004).
     */
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate,
        steps: Long?,
        prefs: UserPreferences,
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
     * (56 days, [app.readylytics.health.domain.scoring.ScoringConstants.HRV_SIGMA_WINDOW_DAYS])
     * every day in `[startDate, endDate]` will need, for a caller to hold across a multi-day
     * walk-forward instead of re-querying per day.
     */
    suspend fun fetchWalkForwardBaselineContext(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): WalkForwardBaselineContext

    suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummary

    suspend fun persist(summary: DailySummary)

    suspend fun toReadinessResult(summary: DailySummary): ReadinessResult
}
