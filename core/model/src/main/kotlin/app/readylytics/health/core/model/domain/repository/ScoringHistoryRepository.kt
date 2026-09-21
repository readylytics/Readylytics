package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.HeartRateRecord
import app.readylytics.health.core.model.domain.model.SleepHrSample
import app.readylytics.health.core.model.domain.model.SleepSession
import java.time.LocalDate
import java.time.ZoneId

interface ScoringHistoryRepository {
    suspend fun getSleepSessionsSince(fromMs: Long): List<SleepSession>

    suspend fun getSleepSessionsBetween(
        fromMs: Long,
        toMs: Long,
    ): List<SleepSession>

    suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample>

    suspend fun getAvgSleepHrForSessions(sessionIds: List<String>): Map<String, Int>

    suspend fun getMinHrTimestamp(sessionId: String): Long?

    suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int>

    suspend fun getSleepRmssdForSessionsMap(sessionIds: List<String>): Map<String, List<Float>>

    suspend fun getSleepRmssdForSession(sessionId: String): List<Float>

    suspend fun getRmssdInTimeRange(
        fromMs: Long,
        toMs: Long,
    ): List<Float>

    suspend fun getDailySummaryByDate(
        dateMidnightMs: Long,
        zoneId: ZoneId,
    ): DailySummary?

    suspend fun getAllDailySummaries(zoneId: ZoneId): List<DailySummary>

    /**
     * Task 4: one bulk read of every already-persisted [DailySummary] on/after [fromMs] --
     * retention-bounded, ascending by day. Backs the durable, parameter-only Training Readiness
     * projection recompute (`TrainingReadinessProjectionRecomputeUseCase`), which must not read
     * per-day or touch any other DAO.
     */
    suspend fun getDailySummariesSince(
        fromMs: Long,
        zoneId: ZoneId,
    ): List<DailySummary>

    /**
     * Task 4: batched write-back for [getDailySummariesSince] rows after an in-memory,
     * parameter-only transform (e.g. Training Readiness projection). Callers are responsible for
     * wrapping this in one [TransactionRunner.runInTransaction] so the whole batch commits or rolls
     * back atomically.
     */
    suspend fun upsertDailySummaries(
        summaries: List<DailySummary>,
        zoneId: ZoneId,
    )

    suspend fun getHeartRateRecordsByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecord>

    suspend fun getPreciseHrMax(dateMidnightMs: Long): Double?

    suspend fun getRoundedHrMax(dateMidnightMs: Long): Int?

    suspend fun getPreciseHrvMu(dateMidnightMs: Long): Double?

    suspend fun getPreciseRas(dateMidnightMs: Long): Double?

    suspend fun getRoundedRas(dateMidnightMs: Long): Int?

    suspend fun getPreciseRhrBaseline(dateMidnightMs: Long): Double?

    suspend fun getRoundedRhrBaseline(dateMidnightMs: Long): Int?

    suspend fun hasAnyWorkoutOnlyTrimpData(): Boolean

    suspend fun updateBaselines(
        dateMidnightMs: Long,
        hrvMuMssd: Float?,
        hrvSigmaMssd: Float?,
        rhrBpm: Float?,
        rhrSigma: Float?,
        baselineCalculatedAtDate: LocalDate?,
        hrMax: Float? = null,
        snapshotProfile: String? = null,
        hrvSigmaPrior: Float? = null,
        rasScalingFactor: Float? = null,
        baselineObservationCount: Int? = null,
    )

    /**
     * Task C2 (WP-12, OD-2 gate): cumulative count of distinct eligible sleep-days ("how many
     * nights has the user now accumulated toward calibration maturity") through [endDay]
     * inclusive, scanning the full retained history.
     *
     * Deliberately distinct from the HRV mu/sigma *statistical* windows used for baseline math
     * (`BaselineComputer.computeHrvWindowsBetween`, bounded to
     * `ScoringConstants.HRV_SIGMA_WINDOW_DAYS`/`HRV_MU_WINDOW_DAYS`) -- those must keep their own
     * small rolling windows unchanged. This is the separate, unbounded maturity counter: eligibility
     * uses the same `ScoringCalculator.validateNight` policy as the rest of the scoring pipeline,
     * deduplicated by canonical score-day so two sessions landing on the same day count once.
     *
     * Returns `null` when there is no retained session data through [endDay] at all -- "unknown",
     * never fabricated as zero, and never inferred from a smaller statistical window.
     */
    suspend fun countEligibleSleepDaysThrough(
        endDay: LocalDate,
        zoneId: ZoneId,
    ): Int?

    /**
     * Batch form of [countEligibleSleepDaysThrough]: computes the same cumulative count for every
     * date in [endDays] from a single full-history scan instead of one scan per date -- for
     * callers (e.g. the historical baseline backfill) that need it for many days at once.
     */
    suspend fun countEligibleSleepDaysThroughBatch(
        endDays: List<LocalDate>,
        zoneId: ZoneId,
    ): Map<LocalDate, Int?>
}
