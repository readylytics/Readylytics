package app.readylytics.health.core.database.data.local

import android.util.Log
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
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
 * percentile sketch and SQLite has no `PERCENTILE_CONT`.
 *
 * **PERF-003 (Task 8):** reading every plausible pre-cutoff row into memory in a single pass does
 * not scale -- after a full historical resync the backlog can be 10^6+ rows, and CPU-heavy
 * aggregation (percentiles/histograms) must never run inside a Room writer transaction. So a day
 * is no longer one read-aggregate-publish-delete transaction: [MinuteRollupStreamer] streams the
 * day as keyset-paged samples (`HeartRateDao.pagePlausibleSamplesForRollup`, outside any
 * transaction) and hands [rollupDayChunk] bounded [MinuteRollupStreamer.GROUP_MINUTE_BUDGET]
 * -sized groups of *complete* minutes -- the trailing minute of every page is held back until a
 * later sample proves it closed, so a group boundary can never split a minute (the exact DB-002
 * defect class Phase 1 already fixed once). [publishGroup] aggregates that group's buckets in
 * Kotlin outside any transaction, then opens one *short* transaction per group to publish +
 * delete exactly that group's minutes -- `nextGeneration()` is captured once per group (not once
 * per day), and [MinuteCoveragePublisher.publish] re-validates it inside that group's own
 * transaction, so a concurrent source mutation aborts only that group's publish, not groups
 * already committed earlier in the pass. A day boundary is always a multiple of one minute
 * (86_400_000 / 60_000 = 1440 exactly), so day-chunking still can never split a minute bucket
 * either -- and because `aggregateIntoMinuteBuckets` sorts each minute's own BPM values before
 * computing avg/percentiles, bucket output depends only on which samples share a minute, never on
 * how many pages or groups the run was split into: a heavily-paged run produces byte-identical
 * buckets to a single-pass run (see `DataRollupManagerTest`'s page-size-invariance case).
 *
 * A crash between groups leaves earlier groups already committed and later ones as untouched raw
 * data ready for an idempotent retry -- strictly more crash-safe than one giant transaction, and
 * consistent with this codebase's "worker killed mid-pass leaves prior valid data intact"
 * contract elsewhere (see `RetentionCleanup`, `SessionLinkReconcilerImpl`). A
 * [SourceGenerationConflictException] from one group's publish is caught, logged, and stops the
 * whole pass cleanly (not a crash) -- the next scheduled rollup retries the remaining groups.
 *
 * **WP-17/OD-1:** the visibility switch itself is delegated to [MinuteCoveragePublisher], which
 * runs inside this class's per-group transaction. Minutes whose visible coverage is still the
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
        private val coordinator: HealthMutationCoordinator,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
        private val streamer: MinuteRollupStreamer = MinuteRollupStreamer(heartRateDao),
    ) {
        /**
         * Aggregates and deletes raw heart-rate rows older than [cutoffMs]. R2-CACHE-001: returns
         * the [ScoreInvalidation.AffectedRange] the rollup actually published (the min/max dates of
         * every raw sample that got aggregated into a visible bucket, merged across day-chunks), or
         * `null` when no chunk published any plausible sample. The range is informational only:
         * aging raw samples into the warm tier never journals dirty work or invalidates retained
         * summaries. Minutes quarantined by the OD-1 legacy-coverage rule are not part of it.
         *
         * [pageSize]/[groupMinuteBudget] default to [MinuteRollupStreamer.SAMPLE_PAGE_SIZE] /
         * [MinuteRollupStreamer.GROUP_MINUTE_BUDGET] and exist mainly so tests can force multiple
         * pages/groups over the same fixture to assert byte-identical bucket output (PERF-003).
         */
        suspend fun rollupExpiredHotTier(
            cutoffMs: Long,
            pageSize: Int = MinuteRollupStreamer.SAMPLE_PAGE_SIZE,
            groupMinuteBudget: Int = MinuteRollupStreamer.GROUP_MINUTE_BUDGET,
        ): ScoreInvalidation.AffectedRange? {
            return coordinator.withMutation {
                doRollupExpiredHotTier(cutoffMs, pageSize, groupMinuteBudget)
            }
        }

        private suspend fun doRollupExpiredHotTier(
            cutoffMs: Long,
            pageSize: Int,
            groupMinuteBudget: Int,
        ): ScoreInvalidation.AffectedRange? {
            var touched: ScoreInvalidation.AffectedRange? = null
            val completeCutoff = completeMinuteCutoff(cutoffMs)
            var cursorMs = heartRateDao.getEarliestTimestampMs() ?: completeCutoff

            while (cursorMs < completeCutoff) {
                currentCoroutineContext().ensureActive()
                val dayStart = Math.floorDiv(cursorMs, DAY_MS) * DAY_MS
                val dayEnd = minOf(dayStart + DAY_MS, completeCutoff)
                val chunk = rollupDayChunk(dayStart, dayEnd, pageSize, groupMinuteBudget)
                touched = mergeRanges(touched, chunk.range)
                // A generation conflict means another writer mutated sources mid-pass; stop the
                // whole pass cleanly here rather than continuing to chunk against a moving target
                // (see class KDoc). Groups already committed earlier -- including earlier in this
                // same day chunk -- stay published; the next scheduled rollup retries the rest.
                if (chunk.stoppedOnConflict) return touched
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
            pageSize: Int,
            groupMinuteBudget: Int,
        ): DayChunkResult {
            var chunkRange: ScoreInvalidation.AffectedRange? = null
            try {
                streamer.streamGroups(fromMs, toMs, pageSize, groupMinuteBudget) { group ->
                    chunkRange = mergeRanges(chunkRange, publishGroup(group))
                }
            } catch (e: SourceGenerationConflictException) {
                Log.w(
                    TAG,
                    "Hot-tier rollup stopped: source generation changed during publication " +
                        "(captured=${e.capturedGeneration}, current=${e.currentGeneration}). " +
                        "Groups already committed remain published; the next scheduled rollup retries the rest.",
                )
                return DayChunkResult(chunkRange, stoppedOnConflict = true)
            }
            // PERF-003/idempotency: the per-group deletes below only remove minutes that were
            // actually grouped, i.e. carried at least one *plausible* sample. A minute whose only
            // raw rows are implausible (filtered out of every group by the same predicate
            // `pagePlausibleSamplesForRollup` shares with the old single-pass query) never appears
            // in a group and would otherwise survive the rollup forever -- unlike the old
            // single-pass `rollupDayChunk`, which unconditionally deleted the whole day range. This
            // bounded, day-scoped sweep catches exactly those stragglers; it is skipped whenever
            // nothing is left so a fully-clean day chunk still opens exactly one transaction per
            // group (no unconditional extra transaction). `deleteConsumedSamplesInRange`'s own
            // predicate still protects LEGACY_UNKNOWN-quarantined minutes either way.
            if (heartRateDao.countInRange(fromMs, toMs - 1) > 0) {
                transactionRunner.runInTransaction {
                    heartRateDao.deleteConsumedSamplesInRange(fromMs, toMs)
                }
            }
            return DayChunkResult(chunkRange, stoppedOnConflict = false)
        }

        private suspend fun publishGroup(group: RollupGroup): ScoreInvalidation.AffectedRange? {
            val quarantinedMinutes =
                minuteCoverageDao
                    .getLegacyMinutesInRange(group.minuteStartMs, group.minuteEndExclusiveMs)
                    .toSet()
            val samplesByMinute =
                group.samples
                    .groupBy { completeMinuteCutoff(it.timestampMs) }
                    .filterKeys { it !in quarantinedMinutes }
            if (samplesByMinute.isEmpty()) {
                // Fully quarantined group: raw evidence stays in place (OD-1), no generation burned.
                return null
            }

            // Generation is only advanced once there is publishable evidence, so a group that is
            // entirely quarantined neither burns a generation nor appends dirty work. Captured once
            // per GROUP (not once per day), so a concurrent mutation can only ever invalidate the
            // group currently being assembled.
            val generation = nextGeneration()
            val publishableSamples = samplesByMinute.values.flatten()
            val minMs = publishableSamples.minOf { it.timestampMs }
            val maxMs = publishableSamples.maxOf { it.timestampMs }
            // PERF-003: percentile/histogram aggregation happens here, in Kotlin, outside any
            // writer transaction -- only the publish + delete below run inside one.
            val request =
                MinutePublicationRequest(
                    rangeStartMs = group.minuteStartMs,
                    rangeEndExclusiveMs = group.minuteEndExclusiveMs,
                    capturedGeneration = generation,
                    coverage = samplesByMinute.keys.map { coverageFor(it, generation) },
                    contributions = contributionsFor(samplesByMinute, generation),
                    buckets =
                        publishableSamples
                            .aggregateIntoMinuteBuckets()
                            .map { it.copy(generation = generation) },
                )

            // `publisher.publish` re-validates `capturedGeneration` inside this transaction; on
            // drift it throws SourceGenerationConflictException, which the caller (rollupDayChunk)
            // catches to stop the pass without losing groups already committed above.
            val outcome =
                transactionRunner.runInTransaction {
                    val published = publisher.publish(request)
                    if (published.publishedMinutes.isNotEmpty()) {
                        heartRateDao.deleteConsumedSamplesInRange(group.minuteStartMs, group.minuteEndExclusiveMs)
                    }
                    published
                }

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

        /** Outcome of streaming and publishing one UTC day chunk's groups. */
        private data class DayChunkResult(
            val range: ScoreInvalidation.AffectedRange?,
            val stoppedOnConflict: Boolean,
        )

        private companion object {
            private const val DAY_MS = 86_400_000L
            private const val TAG = "DataRollupManager"
        }
    }
