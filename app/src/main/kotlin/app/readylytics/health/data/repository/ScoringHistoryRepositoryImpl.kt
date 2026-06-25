package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepHrSample
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringHistoryRepositoryImpl
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
    ) : ScoringHistoryRepository {
        override suspend fun getSleepSessionsSince(fromMs: Long): List<SleepSessionEntity> =
            sleepSessionDao.getSince(fromMs)

        override suspend fun getSleepSessionsBetween(
            fromMs: Long,
            toMs: Long,
        ): List<SleepSessionEntity> = sleepSessionDao.getBetween(fromMs, toMs)

        override suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample> =
            heartRateDao.getSleepHrProjectionForSessions(sessionIds)

        override suspend fun getAvgSleepHrForSessions(sessionIds: List<String>): Map<String, Int> =
            heartRateDao.getAvgSleepHrForSessions(sessionIds)

        override suspend fun getMinHrTimestamp(sessionId: String): Long? = heartRateDao.getMinHrTimestamp(sessionId)

        override suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int> =
            heartRateDao.getSleepHrSamplesForSession(sessionId)

        override suspend fun getSleepRmssdForSessionsMap(sessionIds: List<String>): Map<String, List<Float>> =
            hrvDao.getSleepRmssdForSessionsMap(sessionIds)

        override suspend fun getSleepRmssdForSession(sessionId: String): List<Float> =
            hrvDao.getSleepRmssdForSession(sessionId)

        override suspend fun getRmssdInTimeRange(
            fromMs: Long,
            toMs: Long,
        ): List<Float> = hrvDao.getRmssdInTimeRange(fromMs, toMs)

        override suspend fun getDailySummaryByDate(dateMidnightMs: Long): DailySummaryEntity? =
            dailySummaryDao.getByDate(dateMidnightMs)
    }
