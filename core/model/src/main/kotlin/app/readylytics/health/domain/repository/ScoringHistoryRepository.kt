package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.persistence.SleepHrSample

interface ScoringHistoryRepository {
    suspend fun getSleepSessionsSince(fromMs: Long): List<SleepSessionEntity>

    suspend fun getSleepSessionsBetween(
        fromMs: Long,
        toMs: Long,
    ): List<SleepSessionEntity>

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

    suspend fun getDailySummaryByDate(dateMidnightMs: Long): DailySummaryEntity?
}
