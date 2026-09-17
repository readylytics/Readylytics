package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.database.data.local.AuthoritativeHrRange
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
        private val authoritativeReader: AuthoritativeHeartRateReader,
    ) : HeartRateRepository {
        /**
         * Fixture convenience (see `ScoringHeartRateDataLoader`'s secondary constructor for the
         * rationale): assembles the reader from the DAOs a Room-backed test already has.
         */
        constructor(
            heartRateDao: HeartRateDao,
            hrvDao: HrvDao,
            minuteBucketDao: MinuteBucketDao,
        ) : this(heartRateDao, hrvDao, AuthoritativeHeartRateReader(heartRateDao, minuteBucketDao))

        override suspend fun getMinHrInRange(
            startTimeMs: Long,
            endTimeMs: Long,
        ): Int? = authoritativeReader.minBpmInRange(startTimeMs, endTimeMs)

        override suspend fun getByTimeRange(
            startTimeMs: Long,
            endTimeMs: Long,
        ): List<HeartRateRecordData> =
            authoritativeReader.rangeIn(startTimeMs, endTimeMs).rawSamples.map { mapToDomain(it) }

        override fun observeSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordData>> =
            heartRateDao.observeSleepHrTimelineForSession(sessionId).map { list -> list.map { mapToDomain(it) } }

        override fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordData>> =
            hrvDao.observeSleepHrvSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        override fun observeSleepHrvTimelineForSession(sessionId: String): Flow<List<HrvRecordData>> =
            hrvDao.observeSleepHrvTimelineForSession(sessionId).map { list -> list.map { mapToDomain(it) } }

        override fun observeByTimeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<List<HeartRateRecordData>> =
            authoritativeReader.observeRange(startMs, endMs).map { range ->
                range.rawSamples.map { mapToDomain(it) }
            }

        override fun observeAggregateByTimeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<HrRangeAggregate?> = heartRateDao.observeAggregateByTimeRange(startMs, endMs)

        override suspend fun getRecoveryWindowSamples(startTimeMs: Long, endTimeMs: Long): HeartRateSeries =
            authoritativeReader.rangeIn(startTimeMs, endTimeMs).toSeries()

        // WP-17: warm tier is re-read once per hot-tier emission rather than observed itself --
        // bucket rollup only ever affects historical (>90-day) windows that don't emit live during
        // an active viewing session, so a suspend read here is sufficient and avoids a second
        // long-lived Flow subscription per chart. See task-7-brief.md for the combine() tradeoff.
        //
        // WP-17 Step 3: the hot/warm split is now decided by `minute_coverage`, not by "is the warm
        // side non-empty". DataRollupManager's cutoff is a continuous instant, so the day at the
        // 90-day boundary routinely has some minutes in each tier -- both sides are therefore
        // always read and concatenated, and the coverage predicate guarantees no minute is in both.
        override fun observeTimelineWithResolution(startMs: Long, endMs: Long): Flow<HeartRateSeries> =
            authoritativeReader
                .observeRange(startMs, endMs)
                .map { it.toSeries() }
                .distinctUntilChanged()
    }

/**
 * Renders one authoritative range as the domain series charts consume. Warm points keep their
 * existing `RECONSTRUCTED` labelling and synthetic ids, so nothing downstream can mistake an
 * interpolated point for a stored sample.
 *
 * Warm buckets are not further clipped to the exact `[startMs, endMs]` window: a bucket overlapping
 * the window contributes all of its reconstructed points, since a minute bucket is the smallest
 * warm-tier granularity available (the established pattern, unchanged).
 */
private fun AuthoritativeHrRange.toSeries(): HeartRateSeries {
    val hot = rawSamples.map { mapToDomain(it) }
    if (warmBuckets.isEmpty()) return HeartRateSeries(hot, HeartRateResolution.RAW)
    val samples = warmSamples()
    val warm =
        buildList(samples.size) {
            samples.forEachIndexed { _, timestampMs, bpm ->
                add(warmSampleToDomain(timestampMs, bpm))
            }
        }
    return HeartRateSeries(
        points = (hot + warm).sortedBy { it.timestampMs },
        resolution = HeartRateResolution.RECONSTRUCTED,
    )
}

// Top-level (not class members) so they're shared across HeartRateRepositoryImpl's methods
// without pushing its member-function count over detekt's TooManyFunctions threshold.
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

private fun warmSampleToDomain(timestampMs: Long, bpm: Int): HeartRateRecordData =
    HeartRateRecordData(
        id = "warm:$timestampMs",
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "RECONSTRUCTED",
        sessionId = null,
        deviceName = null,
    )
