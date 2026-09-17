package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.completeMinuteCutoff
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hot→warm tier rollup: downsamples raw 1-second heart-rate samples older than the hot/warm
 * boundary into 1-minute `hr_minute_buckets`, records the per-source evidence those buckets were
 * derived from, then deletes the rolled-up raw rows.
 *
 * R2-DB-004: aggregation runs in Kotlin ([aggregateIntoMinuteBuckets]) rather than a pure-SQL
 * `INSERT...SELECT`, because the warm-tier bucket now also carries a p5/p25/p50/p75/p95
 * percentile sketch and SQLite has no `PERCENTILE_CONT`. Reading every plausible pre-cutoff row
 * into memory in a single pass does not scale: after a full historical resync the backlog can be
 * 10^6+ rows, and `heart_rate_records` is already the documented high-volume outlier
 * `RetentionCleanup` batches deletes on for the same reason (DB-002). So this processes one
 * epoch-aligned UTC day at a time -- read -> aggregate -> publish -> delete, each step fully
 * atomic in its own transaction via [rollupDayChunk]. A day boundary is always a multiple of one
 * minute (86_400_000 / 60_000 = 1440 exactly), so day-chunking can never split a minute bucket --
 * a chunked run produces byte-identical buckets to a hypothetical single-pass run. A crash
 * between day-chunks leaves earlier days already committed and later days as untouched raw data
 * ready for an idempotent retry -- strictly more crash-safe than one giant transaction, and
 * consistent with this codebase's "worker killed mid-pass leaves prior valid data intact"
 * contract elsewhere (see `RetentionCleanup`, `SessionLinkReconcilerImpl`).
 *
 * **WP-17/OD-1:** the visibility switch itself is delegated to [MinuteCoveragePublisher], which
 * runs inside this class's day-chunk transaction. Minutes whose visible coverage is still the
 * pre-v22 approximate projection (`LEGACY_UNKNOWN`) are NOT republished by an ordinary rollup: a
 * daily rollup is not the authorized complete interval refresh OD-1 requires before legacy
 * coverage may be replaced, and writing source-backed buckets into such a minute would leave it
 * concatenating legacy and source-backed rows. Their raw samples stay in place as quarantine
 * evidence, which is also why the day-chunk loop advances on `max(dayEnd, nextEarliest)` rather
 * than trusting deletion to move the cursor forward.
 *
 * **WP-17 Step 3:** because of that quarantine, a legacy minute can carry raw rows *and* a warm
 * projection at the same time. Readers must therefore never concatenate the two -- they select one
 * tier per minute through [AuthoritativeHeartRateReader], which owns the `minute_coverage`
 * visibility predicate. The gap this KDoc previously deferred to T3 is closed there.
 */
@Singleton
class DataRollupManager
    @Inject
    constructor(
        private val minuteCoverageDao: MinuteCoverageDao,
        private val heartRateDao: HeartRateDao,
        private val publisher: MinuteCoveragePublisher,
        private val transactionRunner: TransactionRunner,
        private val coordinator: HealthMutationCoordinator? = null,
        private val dirtyRangeDao: DirtyRangeDao? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
    ) {
        /**
         * Aggregates and deletes raw heart-rate rows older than [cutoffMs]. R2-CACHE-001: returns
         * the [ScoreInvalidation.AffectedRange] the rollup actually published (the min/max dates of
         * every raw sample that got aggregated into a visible bucket, merged across day-chunks), or
         * `null` when no chunk published any plausible sample -- a no-op rollup enqueues no
         * recompute. Minutes quarantined by the OD-1 legacy-coverage rule are not part of it.
         */
        suspend fun rollupExpiredHotTier(cutoffMs: Long): ScoreInvalidation.AffectedRange? {
            val runner: suspend () -> ScoreInvalidation.AffectedRange? = { doRollupExpiredHotTier(cutoffMs) }
            return if (coordinator != null) {
                coordinator.withMutation { runner() }
            } else {
                runner()
            }
        }

        private suspend fun doRollupExpiredHotTier(cutoffMs: Long): ScoreInvalidation.AffectedRange? {
            var touched: ScoreInvalidation.AffectedRange? = null
            val completeCutoff = completeMinuteCutoff(cutoffMs)
            var cursorMs = heartRateDao.getEarliestTimestampMs() ?: completeCutoff

            while (cursorMs < completeCutoff) {
                currentCoroutineContext().ensureActive()
                val dayStart = Math.floorDiv(cursorMs, DAY_MS) * DAY_MS
                val dayEnd = minOf(dayStart + DAY_MS, completeCutoff)
                touched = mergeRanges(touched, rollupDayChunk(dayStart, dayEnd))
                yield()
                // `dayEnd` is strictly greater than `cursorMs`, so progress is monotonic even when
                // a chunk leaves quarantined raw rows behind; `nextEarliest` still lets a sparse
                // history skip straight to the next day that actually has data.
                val nextEarliest = heartRateDao.getEarliestTimestampMs() ?: completeCutoff
                cursorMs = maxOf(dayEnd, nextEarliest)
            }
            return touched
        }

        private suspend fun rollupDayChunk(
            fromMs: Long,
            toMs: Long,
        ): ScoreInvalidation.AffectedRange? =
            transactionRunner.runInTransaction {
                val rawSamples = heartRateDao.getPlausibleSamplesInRangeForRollup(fromMs, toMs)
                val chunkRange = if (rawSamples.isEmpty()) null else publishChunk(fromMs, toMs, rawSamples)
                heartRateDao.deleteConsumedSamplesInRange(fromMs, toMs)
                chunkRange
            }

        private suspend fun publishChunk(
            fromMs: Long,
            toMs: Long,
            rawSamples: List<HeartRateRecordEntity>,
        ): ScoreInvalidation.AffectedRange? {
            val quarantinedMinutes = minuteCoverageDao.getLegacyMinutesInRange(fromMs, toMs).toSet()
            val samplesByMinute =
                rawSamples
                    .groupBy { completeMinuteCutoff(it.timestampMs) }
                    .filterKeys { it !in quarantinedMinutes }
            if (samplesByMinute.isEmpty()) return null

            // Generation is only advanced once there is publishable evidence, so a chunk that is
            // entirely quarantined neither burns a generation nor appends dirty work.
            val generation = nextGeneration()
            val publishableSamples = samplesByMinute.values.flatten()
            val minMs = publishableSamples.minOf { it.timestampMs }
            val maxMs = publishableSamples.maxOf { it.timestampMs }

            val outcome =
                publisher.publish(
                    MinutePublicationRequest(
                        rangeStartMs = fromMs,
                        rangeEndExclusiveMs = toMs,
                        capturedGeneration = generation,
                        coverage = samplesByMinute.keys.map { coverageFor(it, generation) },
                        contributions = contributionsFor(samplesByMinute, generation),
                        buckets =
                            publishableSamples
                                .aggregateIntoMinuteBuckets()
                                .map { it.copy(generation = generation) },
                        dirtyRange = dirtyRangeFor(minMs, maxMs, generation),
                    ),
                )

            return if (outcome.publishedMinutes.isEmpty()) {
                null
            } else {
                ScoreInvalidation.AffectedRange(
                    start = utcDateOf(minMs),
                    endInclusive = utcDateOf(maxMs),
                )
            }
        }

        private suspend fun nextGeneration(): Long {
            val dao = healthMutationStateDao ?: return 0L
            dao.incrementGeneration()
            return dao.current().sourceGeneration
        }

        private fun coverageFor(
            bucketStartMs: Long,
            generation: Long,
        ) = MinuteCoverageEntity(
            bucketStartMs = bucketStartMs,
            visibleGeneration = generation,
            tier = TIER_WARM,
            quality = QUALITY_SOURCE_BACKED,
            sourceSelectionId = null,
        )

        /**
         * Groups each complete minute's samples by their explicit `sourceRecordRef` -- never by
         * session ID or sketch -- and counts the plausible integer BPM values into a histogram,
         * keeping first/last observed timestamps and device as measured evidence.
         */
        private fun contributionsFor(
            samplesByMinute: Map<Long, List<HeartRateRecordEntity>>,
            generation: Long,
        ): List<HrSourceMinuteContributionEntity> =
            samplesByMinute.flatMap { (bucketStartMs, minuteSamples) ->
                minuteSamples.groupBy { it.sourceRecordRef }.map { (sourceRef, sourceSamples) ->
                    HrSourceMinuteContributionEntity(
                        sourceRecordRef = sourceRef,
                        bucketStartMs = bucketStartMs,
                        generation = generation,
                        firstSampleMs = sourceSamples.minOf { it.timestampMs },
                        lastSampleMs = sourceSamples.maxOf { it.timestampMs },
                        deviceName =
                            sourceSamples
                                .firstOrNull { !it.deviceName.isNullOrBlank() }
                                ?.deviceName
                                .orEmpty(),
                        bpmHistogram =
                            BpmHistogram(sourceSamples.groupingBy { it.beatsPerMinute }.eachCount())
                                .encode(),
                    )
                }
            }

        private fun dirtyRangeFor(
            minMs: Long,
            maxMs: Long,
            generation: Long,
        ): DirtyRangeEntity? {
            if (dirtyRangeDao == null || healthMutationStateDao == null) return null
            val startDate = utcDateOf(minMs)
            val endInclusive = maxOf(LocalDate.now(ZoneOffset.UTC), utcDateOf(maxMs))
            return DirtyRangeEntity(
                sourceGeneration = generation,
                startEpochDay = startDate.toEpochDay(),
                endEpochDayInclusive = endInclusive.toEpochDay(),
                nextEpochDay = startDate.toEpochDay(),
                reason = "HOT_TIER_ROLLUP",
                scoringSnapshotId = "ACTIVE",
            )
        }

        private fun utcDateOf(epochMs: Long): LocalDate =
            Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()

        private fun mergeRanges(
            a: ScoreInvalidation.AffectedRange?,
            b: ScoreInvalidation.AffectedRange?,
        ): ScoreInvalidation.AffectedRange? =
            when {
                a == null -> b
                b == null -> a
                else ->
                    ScoreInvalidation.AffectedRange(
                        start = minOf(a.start, b.start),
                        endInclusive = maxOf(a.endInclusive, b.endInclusive),
                    )
            }

        private companion object {
            private const val DAY_MS = 86_400_000L
        }
    }
