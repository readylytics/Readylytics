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
}
