package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.local.reconstructTimestampedSamples
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.model.domain.model.HrRangeAggregate
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.model.domain.repository.HeartRateSeries
import app.readylytics.health.core.model.domain.repository.HrvRecordData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeartRateRepositoryImpl
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val minuteBucketDao: MinuteBucketDao,
    ) : HeartRateRepository {
        override suspend fun getMinHrInRange(
            startTimeMs: Long,
            endTimeMs: Long,
        ): Int? = heartRateDao.getMinHrInRange(startTimeMs, endTimeMs)

        override suspend fun getByTimeRange(
            startTimeMs: Long,
            endTimeMs: Long,
        ): List<HeartRateRecordData> = heartRateDao.getByTimeRange(startTimeMs, endTimeMs).map { mapToDomain(it) }

        override fun observeSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordData>> =
            heartRateDao.observeSleepHrTimelineForSession(sessionId).map { list -> list.map { mapToDomain(it) } }

        override fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordData>> =
            hrvDao.observeSleepHrvSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        override fun observeByTimeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<List<HeartRateRecordData>> =
            heartRateDao.observeByTimeRange(startMs, endMs).map { list ->
                list.map { mapToDomain(it) }
            }

        override fun observeAggregateByTimeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<HrRangeAggregate?> = heartRateDao.observeAggregateByTimeRange(startMs, endMs)

        override suspend fun getRecoveryWindowSamples(startTimeMs: Long, endTimeMs: Long): HeartRateSeries {
            val hot = heartRateDao.getByTimeRange(startTimeMs, endTimeMs).map { mapToDomain(it) }
            val warmBuckets = minuteBucketDao.getBucketsInTimeRange(startTimeMs, endTimeMs)
            if (warmBuckets.isEmpty()) return HeartRateSeries(hot, HeartRateResolution.RAW)

            // Not further filtered to the exact [startTimeMs, endTimeMs] window: getBucketsInTimeRange
            // already scopes to overlapping buckets, and (matching ScoringHistoryRepositoryImpl's
            // established warm-tier pattern) a bucket that overlaps the window contributes all of its
            // reconstructed points, since a minute bucket is the smallest warm-tier granularity available.
            val warm =
                warmBuckets
                    .reconstructTimestampedSamples()
                    .map { (timestampMs, bpm) -> warmSampleToDomain(timestampMs, bpm) }
            return HeartRateSeries(
                points = (hot + warm).sortedBy { it.timestampMs },
                resolution = HeartRateResolution.RECONSTRUCTED,
            )
        }

        // WP-17: warm tier is re-read once per hot-tier emission rather than observed itself --
        // bucket rollup only ever affects historical (>90-day) windows that don't emit live during
        // an active viewing session, so a suspend read here is sufficient and avoids a second
        // long-lived Flow subscription per chart. See task-7-brief.md for the combine() tradeoff.
        override fun observeTimelineWithResolution(startMs: Long, endMs: Long): Flow<HeartRateSeries> =
            heartRateDao
                .observeByTimeRange(startMs, endMs)
                .map { entities -> entities.map { mapToDomain(it) } }
                .map { hot ->
                    if (hot.isNotEmpty()) return@map HeartRateSeries(hot, HeartRateResolution.RAW)
                    val warmBuckets = minuteBucketDao.getBucketsInTimeRange(startMs, endMs)
                    if (warmBuckets.isEmpty()) return@map HeartRateSeries(emptyList(), HeartRateResolution.RAW)
                    val warm =
                        warmBuckets.reconstructTimestampedSamples().map { (timestampMs, bpm) ->
                            warmSampleToDomain(timestampMs, bpm)
                        }
                    HeartRateSeries(warm, HeartRateResolution.RECONSTRUCTED)
                }.distinctUntilChanged()

        private fun mapToDomain(entity: HeartRateRecordEntity): HeartRateRecordData =
            HeartRateRecordData(
                id = "${entity.sourceRecordRef}:${entity.timestampMs}",
                timestampMs = entity.timestampMs,
                beatsPerMinute = entity.beatsPerMinute,
                recordType = entity.recordType,
                sessionId = entity.sessionId,
                deviceName = entity.deviceName,
            )

        private fun mapToDomain(entity: HrvRecordEntity): HrvRecordData =
            HrvRecordData(
                id = "${entity.sourceRecordRef}:${entity.timestampMs}",
                timestampMs = entity.timestampMs,
                rmssdMs = entity.rmssdMs,
                recordType = entity.recordType,
                sessionId = entity.sessionId,
                deviceName = entity.deviceName,
            )
    }

// Top-level (not a class member) so it's shared by getRecoveryWindowSamples and
// observeTimelineWithResolution without pushing HeartRateRepositoryImpl's member-function count
// over detekt's TooManyFunctions threshold.
private fun warmSampleToDomain(timestampMs: Long, bpm: Int): HeartRateRecordData =
    HeartRateRecordData(
        id = "warm:$timestampMs",
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "RECONSTRUCTED",
        sessionId = null,
        deviceName = null,
    )
