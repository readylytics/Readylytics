package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
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
 * boundary into 1-minute `hr_minute_buckets`, then deletes the rolled-up raw rows.
 *
 * R2-DB-004: aggregation runs in Kotlin ([aggregateIntoMinuteBuckets]) rather than a pure-SQL
 * `INSERT...SELECT`, because the warm-tier bucket now also carries a p5/p25/p50/p75/p95
 * percentile sketch and SQLite has no `PERCENTILE_CONT`. Reading every plausible pre-cutoff row
 * into memory in a single pass does not scale: after a full historical resync the backlog can be
 * 10^6+ rows, and `heart_rate_records` is already the documented high-volume outlier
 * `RetentionCleanup` batches deletes on for the same reason (DB-002). So this processes one
 * epoch-aligned UTC day at a time -- read -> aggregate -> upsert -> delete, each step fully
 * atomic in its own transaction via [rollupDayChunk]. A day boundary is always a multiple of one
 * minute (86_400_000 / 60_000 = 1440 exactly), so day-chunking can never split a minute bucket --
 * a chunked run produces byte-identical buckets to a hypothetical single-pass run. A crash
 * between day-chunks leaves earlier days already committed and later days as untouched raw data
 * ready for an idempotent retry -- strictly more crash-safe than one giant transaction, and
 * consistent with this codebase's "worker killed mid-pass leaves prior valid data intact"
 * contract elsewhere (see `RetentionCleanup`, `SessionLinkReconcilerImpl`).
 *
 * The loop re-queries [HeartRateDao.getEarliestTimestampMs] after every chunk rather than
 * incrementing by a fixed day each time: `deleteInRange` unconditionally removes every raw row in
 * the chunk just processed, so the next earliest timestamp (if any) is guaranteed to land at or
 * after that chunk's end -- monotonic forward progress, no infinite-loop risk -- while letting a
 * sparse historical range (e.g. a 10-year resync with data only in a narrow recent window) skip
 * directly to the next day that actually has data instead of iterating thousands of empty days.
 */
@Singleton
class DataRollupManager
    @Inject
    constructor(
        private val minuteBucketDao: MinuteBucketDao,
        private val heartRateDao: HeartRateDao,
        private val transactionRunner: TransactionRunner,
        private val coordinator: HealthMutationCoordinator? = null,
        private val dirtyRangeDao: DirtyRangeDao? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
    ) {
        /**
         * Aggregates and deletes raw heart-rate rows older than [cutoffMs]. R2-CACHE-001: returns
         * the [ScoreInvalidation.AffectedRange] the rollup actually touched (the min/max dates of
         * every raw sample that got aggregated into a bucket, merged across day-chunks), or `null`
         * when no chunk aggregated any plausible sample -- a no-op rollup enqueues no recompute.
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
            val completeCutoff = app.readylytics.health.core.model.domain.sync.completeMinuteCutoff(cutoffMs)
            var cursorMs = heartRateDao.getEarliestTimestampMs() ?: completeCutoff
            
            while (cursorMs < completeCutoff) {
                currentCoroutineContext().ensureActive()
                val dayStart = Math.floorDiv(cursorMs, DAY_MS) * DAY_MS
                val dayEnd = minOf(dayStart + DAY_MS, completeCutoff)
                touched = mergeRanges(touched, rollupDayChunk(dayStart, dayEnd))
                yield()
                cursorMs = heartRateDao.getEarliestTimestampMs() ?: completeCutoff
            }
            return touched
        }

        private suspend fun rollupDayChunk(
            fromMs: Long,
            toMs: Long,
        ): ScoreInvalidation.AffectedRange? =
            transactionRunner.runInTransaction {
                val rawSamples = heartRateDao.getPlausibleSamplesInRangeForRollup(fromMs, toMs)
                val chunkRange =
                    if (rawSamples.isNotEmpty()) {
                        minuteBucketDao.upsertBuckets(rawSamples.aggregateIntoMinuteBuckets())
                        val minMs = rawSamples.minOf { it.timestampMs }
                        val maxMs = rawSamples.maxOf { it.timestampMs }

                        if (dirtyRangeDao != null && healthMutationStateDao != null) {
                            healthMutationStateDao.incrementGeneration()
                            val currentGen = healthMutationStateDao.current().sourceGeneration
                            val startDate = Instant.ofEpochMilli(minMs).atZone(ZoneOffset.UTC).toLocalDate()
                            val maxDate = Instant.ofEpochMilli(maxMs).atZone(ZoneOffset.UTC).toLocalDate()
                            val today = LocalDate.now(ZoneOffset.UTC)
                            val endInclusive = maxOf(today, maxDate)
                            dirtyRangeDao.insert(
                                DirtyRangeEntity(
                                    sourceGeneration = currentGen,
                                    startEpochDay = startDate.toEpochDay(),
                                    endEpochDayInclusive = endInclusive.toEpochDay(),
                                    nextEpochDay = startDate.toEpochDay(),
                                    reason = "HOT_TIER_ROLLUP",
                                    scoringSnapshotId = "ACTIVE",
                                ),
                            )
                        }

                        ScoreInvalidation.AffectedRange(
                            start = Instant.ofEpochMilli(minMs).atZone(ZoneOffset.UTC).toLocalDate(),
                            endInclusive = Instant.ofEpochMilli(maxMs).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    } else {
                        null
                    }
                heartRateDao.deleteInRange(fromMs, toMs)
                chunkRange
            }

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
