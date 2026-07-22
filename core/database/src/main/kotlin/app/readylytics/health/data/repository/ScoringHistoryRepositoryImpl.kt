package app.readylytics.health.data.repository

import app.readylytics.health.data.mapper.DailySummaryMapper
import app.readylytics.health.data.mapper.HeartRateRecordMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.HeartRateRecord
import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.persistence.HeartRateDao
import app.readylytics.health.domain.persistence.HrvDao
import app.readylytics.health.domain.persistence.SleepHrSample
import app.readylytics.health.domain.persistence.SleepSessionDao
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import java.time.LocalDate
import java.time.ZoneId
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

        override suspend fun getAllDailySummaries(zoneId: ZoneId): List<DailySummary> =
            dailySummaryDao.getAllSummaries().map { DailySummaryMapper.toDomain(it, zoneId) }

        override suspend fun getHeartRateRecordsByTimeRange(
            startMs: Long,
            endMs: Long,
        ): List<HeartRateRecord> = heartRateDao.getByTimeRange(startMs, endMs).map(HeartRateRecordMapper::toDomain)

        override suspend fun getPreciseHrMax(dateMidnightMs: Long): Double? =
            dailySummaryDao.getPreciseHrMax(dateMidnightMs)

        override suspend fun getRoundedHrMax(dateMidnightMs: Long): Int? =
            dailySummaryDao.getRoundedHrMax(dateMidnightMs)

        override suspend fun getPreciseHrvMu(dateMidnightMs: Long): Double? =
            dailySummaryDao.getPreciseHrvMu(dateMidnightMs)

        override suspend fun getPreciseRas(dateMidnightMs: Long): Double? =
            dailySummaryDao.getPreciseRas(dateMidnightMs)

        override suspend fun getRoundedRas(dateMidnightMs: Long): Int? =
            dailySummaryDao.getRoundedRas(dateMidnightMs)

        override suspend fun getPreciseRhrBaseline(dateMidnightMs: Long): Double? =
            dailySummaryDao.getPreciseRhrBaseline(dateMidnightMs)

        override suspend fun getRoundedRhrBaseline(dateMidnightMs: Long): Int? =
            dailySummaryDao.getRoundedRhrBaseline(dateMidnightMs)

        override suspend fun hasAnyWorkoutOnlyTrimpData(): Boolean = dailySummaryDao.hasAnyWorkoutOnlyTrimpData()

        override suspend fun updateBaselines(
            dateMidnightMs: Long,
            hrvMuMssd: Float?,
            hrvSigmaMssd: Float?,
            rhrBpm: Float?,
            rhrSigma: Float?,
            baselineCalculatedAtDate: LocalDate?,
            hrMax: Float?,
            snapshotProfile: String?,
            hrvSigmaPrior: Float?,
            rasScalingFactor: Float?,
            baselineObservationCount: Int?,
        ) {
            dailySummaryDao.updateBaselines(
                dateMidnightMs = dateMidnightMs,
                hrvMuMssd = hrvMuMssd,
                hrvSigmaMssd = hrvSigmaMssd,
                rhrBpm = rhrBpm,
                rhrSigma = rhrSigma,
                baselineCalculatedAtDate = baselineCalculatedAtDate,
                hrMax = hrMax,
                snapshotProfile = snapshotProfile,
                hrvSigmaPrior = hrvSigmaPrior,
                rasScalingFactor = rasScalingFactor,
                baselineObservationCount = baselineObservationCount,
            )
        }
    }
