package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageSelectionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepHrSample
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One time range's authoritative heart-rate evidence. [rawSamples] and [warmBuckets] are
 * guaranteed never to describe the same minute: the two SQL predicates
 * ([HeartRateDao.getVisibleByTimeRange] and [MinuteBucketDao.getVisibleBucketsInTimeRange]) are
 * exact mirrors, so a minute is served either from the hot tier or from exactly one generation of
 * its warm projection. Consumers therefore concatenate the two freely without double-counting.
 */
data class AuthoritativeHrRange(
    val rawSamples: List<HeartRateRecordEntity>,
    val warmBuckets: List<HrMinuteBucketEntity>,
) {
    /** `RAW` while every visible minute is still raw; `RECONSTRUCTED` once any warm slice is in. */
    val resolution: HeartRateResolution
        get() = if (warmBuckets.isEmpty()) HeartRateResolution.RAW else HeartRateResolution.RECONSTRUCTED

    internal fun warmSamples(): TimestampedSamples = warmBuckets.reconstructTimestampedSamples()

    /**
     * The **whole** range as raw-shaped rows: [rawSamples] plus every visible warm bucket
     * reconstructed into synthetic rows that keep that bucket's own `recordType`/`sessionId`/
     * `deviceName` (see [reconstructAsRecords]), ordered ascending by timestamp. The sort is stable,
     * so raw rows keep their `(timestampMs, sourceRecordRef)` order among themselves.
     *
     * **Any consumer that means "every sample in this window" must use this, not [rawSamples]
     * alone.** For a minute the coverage ledger resolves to the warm tier, [rawSamples] is empty
     * *by design* -- the OD-1 quarantine leaves the raw rows physically present but invisible -- so
     * reading only [rawSamples] returns nothing at all for that minute rather than its
     * authoritative evidence. That is strictly lossier than not applying the predicate, and on a
     * scoring path (`ComputeSleepMetricsUseCase` -> `HrCoverageValidator.isValid`, which fails an
     * empty list) it silently withholds a night's score.
     *
     * Warm buckets are not clipped to the exact `[startMs, endMs]` window: a bucket overlapping the
     * window contributes all of its reconstructed points, because a minute is the smallest
     * warm-tier granularity there is. Same established convention as `toSeries()`.
     */
    internal fun mergedSamples(): List<HeartRateRecordEntity> =
        if (warmBuckets.isEmpty()) {
            rawSamples
        } else {
            (rawSamples + warmBuckets.reconstructAsRecords()).sortedBy { it.timestampMs }
        }
}

/**
 * WP-17 Step 3: the single owner of the hot-versus-warm tier-visibility predicate.
 *
 * Before this class, every consumer that wanted "this range's heart rate" read the hot tier and
 * the warm tier independently and concatenated them, which double-counts any minute present in
 * both. That could not happen while rollup always deleted the raw rows it consumed -- but the OD-1
 * legacy-quarantine rule (see [DataRollupManager]) deliberately leaves raw rows in place below the
 * hot/warm cutoff for minutes whose coverage is still `LEGACY_UNKNOWN`, so overlap is now a real
 * state. T2 recorded the per-minute ledger (`minute_coverage`) that resolves it; this class is the
 * one place that applies it.
 *
 * The rules, identical for means, percentile projections, charts, workout HR and baseline
 * coverage/count checks:
 * - a minute with **no** `minute_coverage` row is raw-only (freshly ingested, or never rolled up);
 * - a minute with `HOT` coverage is raw-only, and its older warm projections are hidden;
 * - a minute with `WARM`/`LEGACY_WARM` coverage is warm-only, and only the bucket slices at that
 *   row's `visibleGeneration` are visible -- a superseded generation left behind by a crash stays
 *   invisible;
 * - an overlap is never summed or concatenated. It is resolved in favour of exactly one tier.
 *
 * Consumers ([app.readylytics.health.core.database.data.repository.HeartRateRepositoryImpl],
 * [app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl],
 * [app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader] and
 * [SessionLinkReconcilerImpl]) delegate here instead of holding their own predicate SQL. Public
 * callers keep going through the existing repository interfaces; only the repository/loader
 * internals changed.
 *
 * Reads only -- no transaction of its own, so it composes inside a caller's transaction as well as
 * outside one.
 */
@Singleton
class AuthoritativeHeartRateReader
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val minuteBucketDao: MinuteBucketDao,
        private val coverageSelectionDao: MinuteCoverageSelectionDao? = null,
    ) {
        /**
         * Authoritative evidence for `[startMs, endMs]` (inclusive end, matching the
         * `getByTimeRange` contract every caller of this range shape already uses).
         */
        suspend fun rangeIn(
            startMs: Long,
            endMs: Long,
        ): AuthoritativeHrRange =
            AuthoritativeHrRange(
                rawSamples = heartRateDao.getVisibleByTimeRange(startMs, endMs),
                warmBuckets = minuteBucketDao.getVisibleBucketsInTimeRange(startMs, endMs),
            )

        /**
         * Observable form of [rangeIn] with an exclusive `endMs`, matching
         * [HeartRateDao.observeByTimeRange]'s existing convention.
         *
         * WP-17: the warm tier is re-read once per hot-tier emission rather than observed itself,
         * preserving the established tradeoff (rollup only ever affects historical windows that do
         * not emit live during a viewing session) -- see `HeartRateRepositoryImpl`'s note.
         */
        fun observeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<AuthoritativeHrRange> =
            heartRateDao.observeVisibleByTimeRange(startMs, endMs).map { raw ->
                AuthoritativeHrRange(
                    rawSamples = raw,
                    warmBuckets = minuteBucketDao.getVisibleBucketsInTimeRange(startMs, endMs),
                )
            }

        /**
         * Visible raw rows of one record type in `[startMs, endMs]`. Backed by
         * `index_hr_v10_type_timestamp`, so a workout-HR caller still never pulls a range's sleep
         * or resting samples into memory to discard them (DB-001).
         */
        suspend fun rawByTypeInRange(
            recordType: String,
            startMs: Long,
            endMs: Long,
        ): List<HeartRateRecordEntity> = heartRateDao.getVisibleByTypeAndTimeRange(recordType, startMs, endMs)

        /**
         * Warm-tier samples of one session, reconstructed as raw-shaped rows so consumers that
         * only ever saw raw samples keep working. `sourceRecordRef = 0` marks a reconstructed row:
         * it references no `health_source_records` row and is never persisted.
         */
        suspend fun warmSessionSamples(
            recordType: String,
            sessionId: String,
        ): List<HeartRateRecordEntity> {
            val samples =
                minuteBucketDao
                    .getVisibleBucketsForSession(recordType, sessionId)
                    .reconstructTimestampedSamples()
            if (samples.isEmpty) return emptyList()
            return buildList(samples.size) {
                samples.forEachIndexed { _, timestampMs, bpm ->
                    add(
                        HeartRateRecordEntity(
                            sourceRecordRef = 0L,
                            timestampMs = timestampMs,
                            beatsPerMinute = bpm,
                            recordType = recordType,
                            sessionId = sessionId,
                        ),
                    )
                }
            }
        }

        /**
         * Per-minute mean/count projection for `[dayStartMs, dayEndMs)`, merged across both tiers.
         * Because the predicates partition the minutes, no `bucketIndex` can appear on both sides;
         * the weighted merge is retained purely as a defensive identity (it reduces to a plain
         * union) and keeps the everyday-HR load calculator's ascending bucket order.
         */
        suspend fun minuteBuckets(
            dayStartMs: Long,
            dayEndMs: Long,
        ): List<HrMinuteBucketRow> {
            val hot = heartRateDao.getVisibleMinuteBuckets(dayStartMs, dayEndMs)
            val warm = minuteBucketDao.getVisibleMinuteBuckets(dayStartMs, dayEndMs)
            return when {
                warm.isEmpty() -> hot
                hot.isEmpty() -> warm
                else -> mergeMinuteBucketRows(hot, warm)
            }
        }

        /**
         * Sleep-HR projection for several sessions at once, ordered by `(sessionId, bpm)` exactly
         * as the pure hot-tier query was -- this feeds the baseline eligibility count and the
         * per-session sleep mean, so its ordering and multiset must not change.
         */
        suspend fun sleepProjectionForSessions(sessionIds: List<String>): List<SleepHrSample> {
            if (sessionIds.isEmpty()) return emptyList()
            val hot = heartRateDao.getVisibleSleepHrProjectionForSessions(sessionIds)
            val warm =
                sessionIds.flatMap { sessionId ->
                    minuteBucketDao
                        .getVisibleBucketsForSession(RecordType.SLEEP.name, sessionId)
                        .reconstructSampleValues()
                        .map { SleepHrSample(sessionId = sessionId, beatsPerMinute = it) }
                }
            return if (warm.isEmpty()) {
                hot
            } else {
                (hot + warm).sortedWith(compareBy({ it.sessionId }, { it.beatsPerMinute }))
            }
        }

        /** Ascending sleep-HR sample values of one session across both tiers. */
        suspend fun sleepSamplesForSession(sessionId: String): List<Int> {
            val hot = heartRateDao.getVisibleSleepHrSamplesForSession(sessionId)
            val warmBuckets = minuteBucketDao.getVisibleBucketsForSession(RecordType.SLEEP.name, sessionId)
            if (warmBuckets.isEmpty()) return hot
            return (hot + warmBuckets.reconstructSampleValues().toList()).sorted()
        }

        /**
         * Lowest plausible BPM visible in `[startMs, endMs]`. The warm side takes each visible
         * bucket's stored `minBpm` rather than a reconstructed value, so the answer is the true
         * minimum of the original samples that minute contained, not an interpolation of it.
         *
         * The warm side keeps the bucket-overlap semantics every other warm read in this class uses
         * (a minute is the smallest warm granularity), so the minimum may come from up to 59s
         * outside the window while the raw side is strictly inside it. Deliberate: clipping would
         * mean discarding a partially overlapping minute's only stored minimum, which is a worse
         * answer than one that is at most one minute wide.
         */
        suspend fun minBpmInRange(
            startMs: Long,
            endMs: Long,
        ): Int? {
            val hot = heartRateDao.getVisibleMinHrInRange(startMs, endMs)
            val warm =
                minuteBucketDao
                    .getVisibleBucketsInTimeRange(startMs, endMs)
                    .filter { it.minBpm in MIN_PLAUSIBLE_BPM..MAX_PLAUSIBLE_BPM }
                    .minOfOrNull { it.minBpm }
            return when {
                hot == null -> warm
                warm == null -> hot
                else -> minOf(hot, warm)
            }
        }

        /**
         * How many minutes in `[startMs, endMs)` are still the pre-v22 approximate projection --
         * the measured size of what the relink pass cannot repair and only an authorized complete
         * interval refresh can (OD-1). Returns `0` when the selection DAO is absent (test
         * constructions that predate it).
         */
        suspend fun legacyApproximateMinutes(
            startMs: Long,
            endMs: Long,
        ): Int = coverageSelectionDao?.countLegacyMinutesInRange(startMs, endMs) ?: 0

        private companion object {
            const val MIN_PLAUSIBLE_BPM = 30
            const val MAX_PLAUSIBLE_BPM = 230
        }
    }

/**
 * Weighted per-`bucketIndex` merge of two minute projections. Top-level rather than a member so
 * [AuthoritativeHeartRateReader]'s public function count stays well under detekt's
 * `TooManyFunctions` threshold.
 */
private fun mergeMinuteBucketRows(
    hot: List<HrMinuteBucketRow>,
    warm: List<HrMinuteBucketRow>,
): List<HrMinuteBucketRow> {
    val weightedSums = LinkedHashMap<Int, Double>()
    val counts = LinkedHashMap<Int, Int>()
    for (row in hot + warm) {
        weightedSums[row.bucketIndex] = (weightedSums[row.bucketIndex] ?: 0.0) + row.avgBpm * row.sampleCount
        counts[row.bucketIndex] = (counts[row.bucketIndex] ?: 0) + row.sampleCount
    }
    return counts.keys
        .sorted()
        .map { index ->
            val count = counts.getValue(index)
            HrMinuteBucketRow(
                bucketIndex = index,
                avgBpm = weightedSums.getValue(index) / count,
                sampleCount = count,
            )
        }
}
