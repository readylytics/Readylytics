package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.domain.util.HeartRateFormulas
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE
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
         */
        suspend fun recomputeDay(
            day: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
        ): Result<Unit> =
            try {
                scoringRepository.computeAndPersistDailySummary(day, steps, prefs)
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
         * PERF-002/WP-20/WP-22: same as the 3-arg overload, but reads/writes the TRIMP series
         * through [trimpContext] and the RHR/HRV baseline windows through [baselineContext] instead
         * of independently re-querying their own lookback windows for this one day. A multi-day
         * walk-forward must build one [trimpContext] (via [buildWalkForwardTrimpContext]) and one
         * [baselineContext] (via [buildWalkForwardBaselineContext]) and pass both to every day it
         * recomputes in that run.
         */
        suspend fun recomputeDay(
            day: LocalDate,
            steps: Long?,
            prefs: UserPreferences,
            trimpContext: WalkForwardTrimpContext,
            baselineContext: WalkForwardBaselineContext,
        ): Result<Unit> =
            try {
                scoringRepository.computeAndPersistDailySummary(day, steps, prefs, trimpContext, baselineContext)
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
