package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.preferences.UserPreferences
import java.time.LocalDate

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

    suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummary

    suspend fun persist(summary: DailySummary)

    suspend fun toReadinessResult(summary: DailySummary): ReadinessResult
}
