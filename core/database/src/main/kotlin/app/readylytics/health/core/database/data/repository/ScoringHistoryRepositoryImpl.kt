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
import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import java.time.Instant
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
        private val authoritativeReader: AuthoritativeHeartRateReader,
        // Task C2: eligibility (validateNight) for countEligibleSleepDaysThrough{,Batch}. Defaulted
        // to a real (pure, zero-arg-constructible) calculator so the many existing 5-arg call sites
        // across the test suite keep compiling; production DI always supplies the bound
        // ScoringCalculator implementation via ScoringBindsModule regardless of this default.
        private val scoringCalculator: ScoringCalculator =
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(LoadScoringStrategy()),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = LoadScoringStrategy(),
            ),
    ) : ScoringHistoryRepository {
        /**
         * Fixture convenience (see `ScoringHeartRateDataLoader`'s secondary constructor for the
         * rationale): assembles the reader from the DAOs a Room-backed test already has.
         */
        constructor(
            heartRateDao: HeartRateDao,
            hrvDao: HrvDao,
            sleepSessionDao: SleepSessionDao,
            dailySummaryDao: DailySummaryDao,
            minuteBucketDao: MinuteBucketDao,
            scoringCalculator: ScoringCalculator =
                CompositeScoringCalculator(
                    sleepStrategy = SleepScoringStrategy(LoadScoringStrategy()),
                    rasStrategy = RasScoringStrategy(),
                    loadStrategy = LoadScoringStrategy(),
                ),
        ) : this(
            heartRateDao = heartRateDao,
            hrvDao = hrvDao,
            sleepSessionDao = sleepSessionDao,
            dailySummaryDao = dailySummaryDao,
            authoritativeReader = AuthoritativeHeartRateReader(heartRateDao, minuteBucketDao),
            scoringCalculator = scoringCalculator,
        )

        override suspend fun getSleepSessionsSince(fromMs: Long): List<SleepSession> =
            sleepSessionDao.getSince(fromMs).map(SleepSessionMapper::toDomain)

        override suspend fun getSleepSessionsBetween(
            fromMs: Long,
            toMs: Long,
        ): List<SleepSession> = sleepSessionDao.getBetween(fromMs, toMs).map(SleepSessionMapper::toDomain)

        // WP-17 Step 3: both tiers are selected by the one coverage predicate in
        // AuthoritativeHeartRateReader, so a minute quarantined by OD-1 contributes its warm
        // projection only -- never its quarantined raw rows as well. This projection feeds the
        // per-session sleep mean AND the baseline eligibility count, so both now agree on sample
        // count and weighted mean with every other reader.
        override suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample> =
            authoritativeReader.sleepProjectionForSessions(sessionIds).map {
                SleepHrSample(sessionId = it.sessionId, beatsPerMinute = it.beatsPerMinute)
            }

        override suspend fun getAvgSleepHrForSessions(sessionIds: List<String>): Map<String, Int> =
            getSleepHrProjectionForSessions(sessionIds)
                .groupBy { it.sessionId }
                .mapValues { (_, samples) -> round(samples.map { it.beatsPerMinute }.average()).toInt() }

        override suspend fun getMinHrTimestamp(sessionId: String): Long? = heartRateDao.getMinHrTimestamp(sessionId)

        override suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int> =
            authoritativeReader.sleepSamplesForSession(sessionId)

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

        override suspend fun getDailySummariesSince(
            fromMs: Long,
            zoneId: ZoneId,
        ): List<DailySummary> = dailySummaryDao.getSince(fromMs).map { DailySummaryMapper.toDomain(it, zoneId) }

        override suspend fun upsertDailySummaries(
            summaries: List<DailySummary>,
            zoneId: ZoneId,
        ) {
            dailySummaryDao.upsertAll(summaries.map { DailySummaryMapper.toEntity(it, zoneId) })
        }

        override suspend fun getHeartRateRecordsByTimeRange(
            startMs: Long,
            endMs: Long,
        ): List<HeartRateRecord> =
            authoritativeReader
                .rangeIn(startMs, endMs)
                .rawSamples
                .map(HeartRateRecordMapper::toDomain)

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

        override suspend fun countEligibleSleepDaysThrough(
            endDay: LocalDate,
            zoneId: ZoneId,
        ): Int? = countEligibleSleepDaysThroughBatch(listOf(endDay), zoneId)[endDay]

        override suspend fun countEligibleSleepDaysThroughBatch(
            endDays: List<LocalDate>,
            zoneId: ZoneId,
        ): Map<LocalDate, Int?> {
            // endDays.maxOrNull() is null only when endDays itself is empty, in which case every
            // associateWith below is a no-op over an empty list regardless of the sessions fetched
            // -- so the empty-endDays case needs no separate early return.
            val sessions =
                endDays
                    .maxOrNull()
                    ?.let { maxEndDay ->
                        val maxEndMs = maxEndDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
                        getSleepSessionsBetween(0L, maxEndMs)
                    }.orEmpty()
            if (sessions.isEmpty()) return endDays.associateWith { null }

            val sessionIds = sessions.map { it.id }
            val hrvMap = getSleepRmssdForSessionsMap(sessionIds)
            val hrMap = getAvgSleepHrForSessions(sessionIds)
            val eligibleScoreDays =
                sessions
                    .filter { session -> isEligibleForBaseline(session, hrvMap, hrMap) }
                    .map { Instant.ofEpochMilli(it.endTime).atZone(zoneId).toLocalDate() }
                    .distinct()
            return endDays.associateWith { endDay -> eligibleScoreDays.count { it <= endDay } }
        }

        private fun isEligibleForBaseline(
            session: SleepSession,
            hrvMap: Map<String, List<Float>>,
            hrMap: Map<String, Int>,
        ): Boolean {
            val samples = hrvMap[session.id].orEmpty()
            val avgHr = hrMap[session.id]
            return scoringCalculator
                .validateNight(
                    rmssdMs = if (samples.isNotEmpty()) samples.average().toFloat() else null,
                    rhrBpm = avgHr?.toFloat(),
                    durationMinutes = session.durationMinutes,
                    deepMinutes = session.deepSleepMinutes,
                    remMinutes = session.remSleepMinutes,
                ).canContributeToBaseline
        }
    }
