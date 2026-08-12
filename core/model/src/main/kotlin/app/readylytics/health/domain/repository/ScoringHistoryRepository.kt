package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.HeartRateRecord
import app.readylytics.health.domain.model.SleepHrSample
import app.readylytics.health.domain.model.SleepSession
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
