package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.database.data.mapper.HeartRateRecordMapper
import app.readylytics.health.core.database.data.mapper.SleepSessionMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.HeartRateRecord
import app.readylytics.health.core.model.domain.model.SleepHrSample
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.database.data.local.reconstructSampleValues
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

@Singleton
@Suppress("TooManyFunctions") // Implements every member of the ScoringHistoryRepository domain
// interface (core/model); splitting the interface to shrink this count is a cross-module change
// with call-site impact across ReadinessSummaryCoordinator/ScoringRepositoryImpl, out of Tier 3 scope.
class ScoringHistoryRepositoryImpl
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
        private val minuteBucketDao: MinuteBucketDao,
    ) : ScoringHistoryRepository {
        override suspend fun getSleepSessionsSince(fromMs: Long): List<SleepSession> =
            sleepSessionDao.getSince(fromMs).map(SleepSessionMapper::toDomain)

        override suspend fun getSleepSessionsBetween(
            fromMs: Long,
            toMs: Long,
        ): List<SleepSession> = sleepSessionDao.getBetween(fromMs, toMs).map(SleepSessionMapper::toDomain)

        override suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample> {
            val hot =
                heartRateDao.getSleepHrProjectionForSessions(sessionIds).map {
                    SleepHrSample(sessionId = it.sessionId, beatsPerMinute = it.beatsPerMinute)
                }
            val hotSessionIds = hot.map { it.sessionId }.toSet()
            val warmOnly = sessionIds.filter { it !in hotSessionIds }
            if (warmOnly.isEmpty()) return hot
            val warmSamples =
                warmOnly.flatMap { sessionId ->
                    minuteBucketDao
                        .getBucketsForSession("SLEEP", sessionId)
                        .reconstructSampleValues()
                        .sorted()
                        .map { SleepHrSample(sessionId = sessionId, beatsPerMinute = it) }
                }
            return (hot + warmSamples).sortedWith(compareBy({ it.sessionId }, { it.beatsPerMinute }))
        }

        override suspend fun getAvgSleepHrForSessions(sessionIds: List<String>): Map<String, Int> {
            val hot = heartRateDao.getAvgSleepHrForSessions(sessionIds)
            val warmOnly = sessionIds.filter { it !in hot }
            if (warmOnly.isEmpty()) return hot
            val warm =
                warmOnly.mapNotNull { sessionId ->
                    val buckets = minuteBucketDao.getBucketsForSession("SLEEP", sessionId)
                    val total = buckets.sumOf { it.sampleCount }
                    if (total == 0) {
                        null
                    } else {
                        sessionId to round(buckets.sumOf { it.avgBpm * it.sampleCount } / total).toInt()
                    }
                }.toMap()
            return hot + warm
        }

        override suspend fun getMinHrTimestamp(sessionId: String): Long? = heartRateDao.getMinHrTimestamp(sessionId)

        override suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int> {
            val hot = heartRateDao.getSleepHrSamplesForSession(sessionId)
            if (hot.isNotEmpty()) return hot
            return minuteBucketDao.getBucketsForSession("SLEEP", sessionId).reconstructSampleValues().sorted()
        }

        override suspend fun getSleepRmssdForSessionsMap(sessionIds: List<String>): Map<String, List<Float>> =
            hrvDao.getSleepRmssdForSessionsMap(sessionIds)

        override suspend fun getSleepRmssdForSession(sessionId: String): List<Float> =
            hrvDao.getSleepRmssdForSession(sessionId)

        override suspend fun getRmssdInTimeRange(
            fromMs: Long,
            toMs: Long,
        ): List<Float> = hrvDao.getRmssdInTimeRange(fromMs, toMs)

        override suspend fun getDailySummaryByDate(
            dateMidnightMs: Long,
            zoneId: ZoneId,
        ): DailySummary? = dailySummaryDao.getByDate(dateMidnightMs)?.let { DailySummaryMapper.toDomain(it, zoneId) }

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
