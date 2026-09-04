package app.readylytics.health.core.database.domain.sync

import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.repository.WalkForwardVo2MaxContext
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-day recompute helpers shared by [DailySyncUseCase] and [ResyncRangeUseCase]: a single point
 * of daily score persistence (via the unchanged scoring engine) and the auto-Max-HR refresh that
 * both flows run before recomputing.
 */
@Singleton
class DailyRecomputeSupport
    @Inject
    constructor(
        private val scoringRepository: ScoringRepository,
        private val settingsRepo: SettingsRepository,
        private val transactionRunner: TransactionRunner,
    ) {
        /**
         * Recomputes and persists the daily summary for [day]. Already invoked from an IO context;
         * the repository switches to the injected default dispatcher internally for the
         * CPU-heavy computation.
         *
         * [steps] is null when no fresh step count is available for [day] (older historical days
         * outside the sync window). In that case the repository preserves the stored step count.
         */
        suspend fun recomputeDay(
            day: LocalDate,
            steps: Long?,
        ): Result<Unit> = recomputeDay(day, steps, settingsRepo.userPreferences.first())

        /**
         * Same as the two-arg overload, but with a preferences snapshot supplied by the caller.
         * A multi-day walk-forward (daily sync / resync) must call this with one snapshot shared
         * across every recomputed day, or a preference change mid-walk-forward silently mixes
         * old- and new-preference days (SCORE-004).
         *
         * PERF-002/WP-20/WP-22/WP-27: a multi-day walk-forward also builds one [WalkForwardContexts]
         * per run (via [buildWalkForwardTrimpContext], [buildWalkForwardBaselineContext] and
         * [buildWalkForwardFatigueContext]) and passes the same instance to every day it recomputes,
         * oldest day first, so nothing re-queries its own lookback window per day and the fatigue
         * accumulator decays and adds impulses in the correct order.
         */
        suspend fun recomputeDay(
            day: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
            contexts: WalkForwardContexts = WalkForwardContexts(),
        ): Result<Unit> =
            try {
                scoringRepository.computeAndPersistDailySummary(day, steps, prefs, contexts)
                logD("DailyRecomputeSupport") {
                    "Day $day: scored atomically (steps=${steps?.toString() ?: "preserved"})"
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("DailyRecomputeSupport", e) { "Day $day sync failed" }
                Result.failure("Day $day sync failed", "DAY_SYNC_ERROR")
            }

        /**
         * PERF-002/WP-20: fetches the shared TRIMP-series context once for the whole
         * `[startDate, endDate]` walk-forward; pass the result to every [recomputeDay] call in
         * that run instead of letting each day re-query its own lookback window.
         */
        suspend fun buildWalkForwardTrimpContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardTrimpContext = scoringRepository.fetchWalkForwardTrimpContext(startDate, endDate, zoneId)

        /**
         * PERF-002/WP-22: fetches the shared baseline-window sleep-session context once for the
         * whole `[startDate, endDate]` walk-forward; pass the result to every [recomputeDay] call in
         * that run instead of letting each day re-query its own 30-/56-day lookback window.
         */
        suspend fun buildWalkForwardBaselineContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardBaselineContext = scoringRepository.fetchWalkForwardBaselineContext(startDate, endDate, zoneId)

        /**
         * WP-27: prefetches historical seed impulses once for the whole `[startDate, endDate]`
         * walk-forward for exact retained-history reconstruction; pass the result to every [recomputeDay]
         * call in that run, oldest day first, so the shared accumulator decays and adds impulses in the
         * correct order.
         */
        suspend fun buildWalkForwardFatigueContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardFatigueContext = scoringRepository.fetchWalkForwardFatigueContext(startDate, endDate, zoneId)

        /**
         * PERF: fetches the shared wearable-VO2-Max context once for the whole
         * `[startDate, endDate]` walk-forward; pass the result to every [recomputeDay] call in
         * that run instead of letting each day re-query its own 30-day lookback window.
         */
        suspend fun buildWalkForwardVo2MaxContext(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): WalkForwardVo2MaxContext = scoringRepository.fetchWalkForwardVo2MaxContext(startDate, endDate, zoneId)

        /**
         * F7: runs a whole walk-forward's worth of [recomputeDay] calls inside ONE Room
         * transaction.
         *
         * Room's invalidation tracker fires per table per *transaction*, so N per-day
         * `daily_summaries` upserts (plus the per-day `workout_records` modelTrimp writes
         * `ScoringRepositoryImpl` issues) collapse into a single invalidation round instead of one
         * per day. Every observed DAO `Flow` in the UI — Dashboard's today-summary and 7-day RAS
         * window, Vitals/Sleep/Workouts `observeSince` — therefore re-runs its `SELECT` + mapping
         * once per sync rather than once per synced day, while the user is looking at the screen.
         *
         * Two properties make this safe and are load-bearing:
         * - **Reads see the transaction's own uncommitted writes.** The walk-forward depends on
         *   this: day N sums days N-1..N-6 (`RasTotalsComputer.sumRasLastSixDays`) and reads
         *   day N-1 (`ComputeSleepMetricsUseCase`). Deferring the writes to after the loop instead
         *   would make those reads see stale rows and change the scores.
         * - **The dispatcher switch inside `ScoringRepositoryImpl.computeDailySummary` stays in the
         *   transaction.** Room's `TransactionElement` propagates across `withContext(...)` and
         *   suspend DAO calls resolve it to dispatch back onto the transaction thread
         *   (`RoomDatabase.withTransactionContext` / `DBUtil.getCoroutineContext`).
         *
         * Never perform Health Connect I/O inside [block] — that would pin the transaction thread
         * across a remote IPC read. Cancellation inside [block] rolls the whole unit back; that is
         * intended, because recompute is idempotent and the next run redoes the same range.
         */
        suspend fun <R> inRecomputeTransaction(block: suspend () -> R): R = transactionRunner.runInTransaction(block)

        /** Recalculates estimated Max HR if auto-calculation is enabled (age may have changed). */
        suspend fun refreshAutoMaxHr(prefs: UserPreferences) {
            if (prefs.autoCalculateMaxHr) {
                val calculatedMaxHr = HeartRateFormulas.estimateMaxHr(prefs.age)
                if (calculatedMaxHr != prefs.maxHeartRate) {
                    settingsRepo.updateMaxHeartRate(calculatedMaxHr)
                }
            }
        }
    }
