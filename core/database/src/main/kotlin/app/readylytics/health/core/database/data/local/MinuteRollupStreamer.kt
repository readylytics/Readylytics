package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.sync.completeMinuteCutoff
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** One publication unit: complete minutes only, bounded by [MinuteRollupStreamer.GROUP_MINUTE_BUDGET]. */
data class RollupGroup(
    val samples: List<HeartRateRecordEntity>,
    val minuteStartMs: Long,
    val minuteEndExclusiveMs: Long,
)

/**
 * PERF-003: streams a rollup range as bounded groups of *complete* minutes.
 *
 * Day-bounding never bounded sample count — a dense multi-device day can hold arbitrarily many
 * samples, and the old single read plus `groupBy` retained all of them (plus a sorted copy per
 * bucket) inside the writer transaction. This reads keyset pages of at most [SAMPLE_PAGE_SIZE] rows
 * *outside* any transaction and hands the caller at most [GROUP_MINUTE_BUDGET] minutes at a time.
 *
 * The trailing minute of a page is never emitted: it is carried into the next page until a sample
 * from a later minute proves it complete (or the range ends). A group boundary therefore always
 * falls on a minute boundary, so `aggregateIntoMinuteBuckets` and the coverage/contribution grouping
 * see exactly the sample set a single-pass run would see — no partial-minute bucket can be published
 * (DB-002), and paging cannot change bucket values: `aggregateIntoMinuteBuckets` sorts each minute's
 * BPM values before computing avg/percentiles, so the result depends only on which samples share a
 * minute, never on the order pages delivered them in.
 *
 * `pagePlausibleSamplesForRollup` is ordered `(timestampMs ASC, sourceRecordRef ASC)`, so within a
 * single scan every sample of an earlier minute is always read before any sample of a later minute
 * — a minute can therefore never still be "open" in a later page once a subsequent minute's sample
 * has been observed, which is what makes the carry-forward-until-proven-closed rule below correct.
 */
@Singleton
class MinuteRollupStreamer
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
    ) {
        suspend fun streamGroups(
            fromMs: Long,
            toMs: Long,
            pageSize: Int = SAMPLE_PAGE_SIZE,
            groupMinuteBudget: Int = GROUP_MINUTE_BUDGET,
            onGroup: suspend (RollupGroup) -> Unit,
        ) {
            require(pageSize > 0) { "pageSize must be positive" }
            require(groupMinuteBudget > 0) { "groupMinuteBudget must be positive" }

            var afterTs = Long.MIN_VALUE
            var afterRef = Long.MIN_VALUE
            val carried = ArrayList<HeartRateRecordEntity>()
            var exhausted = false

            while (!exhausted) {
                currentCoroutineContext().ensureActive()
                val page = heartRateDao.pagePlausibleSamplesForRollup(fromMs, toMs, afterTs, afterRef, pageSize)
                exhausted = page.size < pageSize
                if (page.isNotEmpty()) {
                    afterTs = page.last().timestampMs
                    afterRef = page.last().sourceRecordRef
                    carried += page
                }

                val closedThrough =
                    if (exhausted) Long.MAX_VALUE else completeMinuteCutoff(carried.last().timestampMs)
                emitGroups(carried, closedThrough, groupMinuteBudget, onGroup)
                yield()
            }
        }

        /**
         * Emits every buffered minute strictly before [closedThroughExclusive] in groups of at most
         * [groupMinuteBudget] minutes, leaving the still-open minute(s) in [buffer] for the next page.
         */
        private suspend fun emitGroups(
            buffer: ArrayList<HeartRateRecordEntity>,
            closedThroughExclusive: Long,
            groupMinuteBudget: Int,
            onGroup: suspend (RollupGroup) -> Unit,
        ) {
            while (true) {
                val closed = buffer.filter { completeMinuteCutoff(it.timestampMs) < closedThroughExclusive }
                if (closed.isEmpty()) return
                val minutes = closed.map { completeMinuteCutoff(it.timestampMs) }.distinct().sorted()
                val take = minutes.take(groupMinuteBudget).toSet()
                val groupSamples = closed.filter { completeMinuteCutoff(it.timestampMs) in take }
                buffer.removeAll(groupSamples.toSet())
                onGroup(
                    RollupGroup(
                        samples = groupSamples,
                        minuteStartMs = take.min(),
                        minuteEndExclusiveMs = take.max() + MINUTE_MS,
                    ),
                )
                if (minutes.size <= groupMinuteBudget) return
            }
        }

        companion object {
            const val SAMPLE_PAGE_SIZE: Int = 5_000
            const val GROUP_MINUTE_BUDGET: Int = 60
            private const val MINUTE_MS = 60_000L
        }
    }
