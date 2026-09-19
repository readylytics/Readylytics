package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageSelectionDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.completeMinuteCutoff
import app.readylytics.health.core.model.domain.sync.link.SampleLink
import app.readylytics.health.core.model.domain.sync.link.SessionLinker
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** What one full-range warm relink pass actually did, counted in minutes. */
data class WarmRelinkOutcome(
    val inspected: Int = 0,
    val republished: Int = 0,
    val retired: Int = 0,
    val unresolvedLegacy: Int = 0,
    val unresolvedEvidence: Int = 0,
) {
    operator fun plus(other: WarmRelinkOutcome): WarmRelinkOutcome =
        WarmRelinkOutcome(
            inspected = inspected + other.inspected,
            republished = republished + other.republished,
            retired = retired + other.retired,
            unresolvedLegacy = unresolvedLegacy + other.unresolvedLegacy,
            unresolvedEvidence = unresolvedEvidence + other.unresolvedEvidence,
        )
}

/** One day-chunk's session spans, pre-narrowed once so per-minute resolution stays O(1)-ish. */
private class ChunkSpans(
    val sleep: List<SessionSpan>,
    val workout: List<SessionSpan>,
) {
    fun narrowTo(
        startMs: Long,
        endInclusiveMs: Long,
    ): ChunkSpans =
        ChunkSpans(
            sleep = sleep.filter { it.startTime <= endInclusiveMs && it.endTime >= startMs },
            workout = workout.filter { it.startTime <= endInclusiveMs && it.endTime >= startMs },
        )

    fun resolve(sampleMs: Long): SampleLink = SessionLinker.resolve(sampleMs, sleep, workout)
}

/**
 * WP-17 Step 4: re-derives every warm minute's `(recordType, sessionId)` projection from stable
 * evidence, once over the complete range, after all ingest/prune.
 *
 * **Why this exists.** `hr_minute_buckets`'s primary key *contains* `recordType` and `sessionId`,
 * so a warm minute does not merely record a session link -- it is keyed by it. When the
 * authoritative session list changes (a sleep session's bounds shift, a workout is edited, a
 * session straddling a minute or chunk boundary is re-ingested under a different chunk alignment),
 * the raw tier is simply re-tagged in place by [SessionLinkReconcilerImpl], but a warm minute has
 * to be re-keyed, which means rewriting rows. This class does that rewrite and routes every write
 * through [MinuteCoveragePublisher], so the "never both raw and warm, exactly one generation per
 * minute" invariant stays enforced by one piece of code.
 *
 * **Why it cannot drift.** Each pass derives the projection from the *immutable* per-source
 * contributions the minute's visible generation was published from
 * ([reconstructEvidence]) -- never from the previous pass's linked buckets -- and re-aggregates
 * them through the very same [aggregateIntoMinuteBuckets] the rollup uses. For a minute whose
 * session assignment is unchanged the result is bit-identical to what is already stored (the
 * histogram is a lossless value multiset), so the pass detects no change and writes nothing. For a
 * minute whose assignment did change, the output is a pure function of the stored evidence plus the
 * session list, so repeating the pass converges immediately instead of progressively reconstructing
 * its own output.
 *
 * **What it does not promise.** `LEGACY_UNKNOWN` minutes (pre-v22 projections and old-archive
 * restores) carry no per-source timestamp/sample evidence at all. They cannot be relinked exactly,
 * so they are left entirely untouched and merely *counted* in [WarmRelinkOutcome.unresolvedLegacy]
 * -- publishing a newly "corrected" canonical score on guessed lineage is exactly what OD-1
 * forbids; only an authorized complete interval refresh can repair them. A minute whose stored
 * histogram is unreadable is treated the same way ([WarmRelinkOutcome.unresolvedEvidence]) rather
 * than aborting the whole pass.
 *
 * **Source deletions converge here.** `SourceRecordDao.deleteBySourceRecordId` removes that
 * source's contributions (and cascades its raw rows) but cannot re-project the minutes that lost
 * them, because a contribution carries no `recordType`/`sessionId`. This pass rebuilds each minute
 * from whatever contributions *remain*, and retires a minute whose evidence is gone entirely, so
 * the deletion converges on the next reconcile instead of leaving a warm projection no evidence
 * backs.
 */
@Singleton
class WarmTierRelinker
    @Inject
    constructor(
        private val selectionDao: MinuteCoverageSelectionDao,
        private val minuteCoverageDao: MinuteCoverageDao,
        private val minuteBucketDao: MinuteBucketDao,
        private val publisher: MinuteCoveragePublisher,
        private val transactionRunner: TransactionRunner,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
    ) {
        /**
         * Relinks every covered minute in `[startMs, endMs]` (inclusive end, matching
         * `SessionLinkReconciler.reconcile`'s window). [sleepSpans]/[workoutSpans] must be the
         * complete session list for that window -- the whole point of running once over the full
         * range rather than per ingest chunk.
         */
        suspend fun relink(
            startMs: Long,
            endMs: Long,
            sleepSpans: List<SessionSpan>,
            workoutSpans: List<SessionSpan>,
        ): WarmRelinkOutcome {
            val rangeStart = completeMinuteCutoff(startMs)
            val rangeEndExclusive = completeMinuteCutoff(endMs) + MINUTE_MS
            if (rangeEndExclusive <= rangeStart) return WarmRelinkOutcome()

            val allSpans = ChunkSpans(sleepSpans, workoutSpans)
            var cursor = rangeStart
            var outcome = WarmRelinkOutcome()
            while (cursor < rangeEndExclusive) {
                currentCoroutineContext().ensureActive()
                // Skips straight over uncovered stretches (rowid seek) instead of walking
                // thousands of empty day-chunks on an install whose history was never rolled up.
                val nextCovered =
                    selectionDao
                        .nextCoveredMinuteAtOrAfter(cursor)
                        ?.takeIf { it < rangeEndExclusive }
                        ?: break
                val dayStart = Math.floorDiv(nextCovered, DAY_MS) * DAY_MS
                val chunkStart = maxOf(dayStart, cursor)
                val chunkEnd = minOf(dayStart + DAY_MS, rangeEndExclusive)
                outcome += relinkChunk(chunkStart, chunkEnd, allSpans.narrowTo(chunkStart, chunkEnd - 1))
                // `chunkEnd > chunkStart >= cursor` always holds, so the cursor advances even for
                // a chunk that publishes nothing -- the loop can never spin on a quarantined day.
                cursor = chunkEnd
                yield()
            }
            return outcome
        }

        /**
         * One day-chunk, fully atomic. A crash between chunks leaves earlier chunks committed and
         * later ones untouched, and a retry re-derives the same result from the same evidence --
         * the same crash-safety shape [DataRollupManager] uses, for the same reason.
         */
        private suspend fun relinkChunk(
            fromMs: Long,
            toMs: Long,
            spans: ChunkSpans,
        ): WarmRelinkOutcome =
            transactionRunner.runInTransaction {
                val capturedGeneration = healthMutationStateDao?.current()?.sourceGeneration ?: 0L
                val retired = retireEvidencelessMinutes(fromMs, toMs)
                val unresolvedLegacy = selectionDao.countLegacyMinutesInRange(fromMs, toMs)
                val contributionsByMinute =
                    selectionDao
                        .getVisibleSourceBackedContributions(fromMs, toMs)
                        .groupBy { it.bucketStartMs }
                if (contributionsByMinute.isEmpty()) {
                    return@runInTransaction WarmRelinkOutcome(
                        retired = retired,
                        unresolvedLegacy = unresolvedLegacy,
                    )
                }

                val existingByMinute =
                    minuteBucketDao
                        .getVisibleBucketsInMinuteRange(fromMs, toMs)
                        .groupBy { it.bucketStartMs }
                val plan = buildPlan(contributionsByMinute, existingByMinute, spans)
                publishPlan(plan, contributionsByMinute, fromMs, toMs, capturedGeneration)

                WarmRelinkOutcome(
                    inspected = contributionsByMinute.size,
                    republished = plan.buckets.size,
                    retired = retired,
                    unresolvedLegacy = unresolvedLegacy,
                    unresolvedEvidence = plan.unreadableMinutes,
                )
            }

        private suspend fun retireEvidencelessMinutes(
            fromMs: Long,
            toMs: Long,
        ): Int {
            val minutes = selectionDao.getEvidencelessSourceBackedMinutes(fromMs, toMs)
            if (minutes.isEmpty()) return 0
            minutes.chunked(MINUTE_KEY_CHUNK).forEach { chunk ->
                minuteBucketDao.deleteBucketsForMinutes(chunk)
                // Contributions too: `getEvidencelessSourceBackedMinutes` only proves nothing
                // remains at the minute's *visible* generation, so a superseded generation's rows
                // can still be sitting in `hr_source_minute_contributions`. Dropping the coverage
                // row without them would orphan those rows permanently -- nothing else ever
                // revisits a minute that has no coverage.
                minuteCoverageDao.deleteContributionsForMinutes(chunk)
                selectionDao.deleteCoverageForMinutes(chunk)
            }
            return minutes.size
        }

        /** The minutes whose freshly derived projection differs from what is currently visible. */
        private fun buildPlan(
            contributionsByMinute: Map<Long, List<HrSourceMinuteContributionEntity>>,
            existingByMinute: Map<Long, List<HrMinuteBucketEntity>>,
            spans: ChunkSpans,
        ): RelinkPlan {
            val changed = LinkedHashMap<Long, List<HrMinuteBucketEntity>>()
            var unreadable = 0
            for (bucketStartMs in contributionsByMinute.keys.sorted()) {
                val outcome =
                    deriveMinute(
                        bucketStartMs = bucketStartMs,
                        contributions = contributionsByMinute.getValue(bucketStartMs),
                        existing = existingByMinute[bucketStartMs].orEmpty(),
                        spans = spans,
                    )
                when (outcome) {
                    MinuteOutcome.Unchanged -> Unit
                    MinuteOutcome.Unreadable -> unreadable++
                    is MinuteOutcome.Changed -> changed[bucketStartMs] = outcome.buckets
                }
            }
            return RelinkPlan(changed, unreadable)
        }

        /** Classifies one minute: already correct, unrepairable, or needing a re-keyed projection. */
        private fun deriveMinute(
            bucketStartMs: Long,
            contributions: List<HrSourceMinuteContributionEntity>,
            existing: List<HrMinuteBucketEntity>,
            spans: ChunkSpans,
        ): MinuteOutcome {
            val minuteSpans = spans.narrowTo(bucketStartMs, bucketStartMs + MINUTE_MS - 1)
            val uniformLink = uniformLinkOrNull(bucketStartMs, minuteSpans)
            if (uniformLink != null && projectionAlreadyMatches(existing, uniformLink, contributions)) {
                return MinuteOutcome.Unchanged
            }
            val derived =
                derivedBucketsOrNull(bucketStartMs) {
                    deriveBuckets(contributions, minuteSpans, bucketStartMs)
                }
            return when {
                // OD-1: unusable lineage is preserved as-is, never replaced by a guess. An *empty*
                // derivation counts as unusable too: publishing it would delete the minute's slices
                // while re-upserting its coverage, leaving a permanently invisible minute that the
                // next pass reads back as `Unchanged`. Preserve and count instead.
                derived.isNullOrEmpty() -> MinuteOutcome.Unreadable
                derived.sortedWith(BUCKET_ORDER) == existing.sortedWith(BUCKET_ORDER) -> MinuteOutcome.Unchanged
                else -> MinuteOutcome.Changed(derived)
            }
        }

        private suspend fun publishPlan(
            plan: RelinkPlan,
            contributionsByMinute: Map<Long, List<HrSourceMinuteContributionEntity>>,
            fromMs: Long,
            toMs: Long,
            capturedGeneration: Long,
        ) {
            if (plan.buckets.isEmpty()) return
            val coverageByMinute =
                minuteCoverageDao.getCoverageInRange(fromMs, toMs).associateBy { it.bucketStartMs }
            val minutes = plan.buckets.keys
            publisher.publish(
                MinutePublicationRequest(
                    rangeStartMs = fromMs,
                    rangeEndExclusiveMs = toMs,
                    capturedGeneration = capturedGeneration,
                    // Coverage is re-upserted unchanged: the relink re-keys a projection derived
                    // from evidence that itself did not change, so the minute keeps the same
                    // `visibleGeneration`. Generation identifies the *evidence* set, and burning a
                    // source generation here would spuriously invalidate other writers' captured
                    // generations. Exactly-one-generation-per-minute still holds, because the
                    // publisher deletes the superseded slices in the same transaction as the insert.
                    coverage = minutes.mapNotNull { coverageByMinute[it] },
                    contributions = minutes.flatMap { contributionsByMinute[it].orEmpty() },
                    buckets = plan.buckets.values.flatten(),
                    dirtyRange = null,
                ),
            )
        }

        /**
         * Rebuilds the minute's slices by reconstructing every contribution's evidence, resolving
         * each reconstructed sample against the minute's session list, and re-aggregating through
         * the rollup's own aggregator -- so the derived min/max/avg/count and percentile sketch use
         * identical arithmetic to the projection they replace.
         */
        private fun deriveBuckets(
            contributions: List<HrSourceMinuteContributionEntity>,
            minuteSpans: ChunkSpans,
            bucketStartMs: Long,
        ): List<HrMinuteBucketEntity> {
            val generation = contributions.first().generation
            val relinked =
                contributions
                    .flatMap { it.reconstructEvidence() }
                    .map { sample ->
                        val link = minuteSpans.resolve(sample.timestampMs)
                        HeartRateRecordEntity(
                            sourceRecordRef = 0L,
                            timestampMs = sample.timestampMs,
                            beatsPerMinute = sample.beatsPerMinute,
                            recordType = link.recordType,
                            sessionId = link.sessionId,
                            deviceName = sample.deviceName,
                        )
                    }
            if (relinked.isEmpty()) return emptyList()
            return relinked
                .aggregateIntoMinuteBuckets()
                .filter { it.bucketStartMs == bucketStartMs }
                .map { it.copy(generation = generation) }
        }

        private data class RelinkPlan(
            val buckets: Map<Long, List<HrMinuteBucketEntity>>,
            val unreadableMinutes: Int,
        )
    }

/** What [WarmTierRelinker.deriveMinute] concluded about one minute. */
private sealed interface MinuteOutcome {
    /** Already correct -- either the fast path matched, or the derivation reproduced it exactly. */
    object Unchanged : MinuteOutcome

    /** Evidence present but unreadable; preserved as-is and counted, never guessed at. */
    object Unreadable : MinuteOutcome

    /** Needs its visible slices rewritten to these re-keyed buckets. */
    data class Changed(
        val buckets: List<HrMinuteBucketEntity>,
    ) : MinuteOutcome
}

// The helpers below are top-level (not class members) so [WarmTierRelinker]'s member count stays
// well under detekt's TooManyFunctions threshold -- the same split HeartRateRepositoryImpl uses.

/**
 * The link shared by the whole minute, or `null` when a session boundary falls inside it.
 * Resolving the minute's own bounds (not its samples') is deliberately conservative: a minute
 * reported non-uniform only takes the slower exact path, never a wrong one.
 */
private fun uniformLinkOrNull(
    bucketStartMs: Long,
    minuteSpans: ChunkSpans,
): SampleLink? {
    val endMs = bucketStartMs + MINUTE_MS - 1
    val hasInteriorBoundary =
        (minuteSpans.sleep + minuteSpans.workout).any {
            it.startTime > bucketStartMs &&
                it.startTime <= endMs ||
                it.endTime >= bucketStartMs &&
                it.endTime < endMs
        }
    if (hasInteriorBoundary) return null
    val atStart = minuteSpans.resolve(bucketStartMs)
    val atEnd = minuteSpans.resolve(bucketStartMs + MINUTE_MS - 1)
    return atStart.takeIf { it == atEnd }
}

/**
 * Fast path for the overwhelmingly common minute: it lies wholly inside one session (or wholly
 * outside every session), its stored slices already carry that session's key, and every sample the
 * remaining evidence accounts for is already in them. Nothing to rewrite, and no per-sample
 * reconstruction is paid for.
 *
 * The sample-count comparison is what makes a *partial* source deletion converge: the remaining
 * contributions then account for fewer samples than the stored projection contains, so the minute
 * falls through to the exact path and is re-projected.
 */
private fun projectionAlreadyMatches(
    existing: List<HrMinuteBucketEntity>,
    link: SampleLink,
    contributions: List<HrSourceMinuteContributionEntity>,
): Boolean {
    val sessionId = link.sessionId ?: ""
    val keysAlreadyMatch =
        existing.isNotEmpty() &&
            existing.all { it.recordType == link.recordType && it.sessionId == sessionId }
    if (!keysAlreadyMatch) return false
    // Unreadable evidence yields null, falling through to the exact path -- which records the
    // minute as unresolved instead of asserting the stored projection is still correct.
    val evidenceCount = evidenceSampleCountOrNull(contributions)
    return evidenceCount != null && existing.sumOf { it.sampleCount } == evidenceCount
}

private fun evidenceSampleCountOrNull(contributions: List<HrSourceMinuteContributionEntity>): Int? =
    try {
        contributions.sumOf { BpmHistogram.decode(it.bpmHistogram).count }
    } catch (e: BpmHistogramFormatException) {
        logI(RELINK_TAG) { "Minute evidence unreadable on fast path: ${e.message}" }
        null
    }

/** Runs a derivation, mapping the typed histogram error to `null` so one bad row is not fatal. */
private inline fun derivedBucketsOrNull(
    bucketStartMs: Long,
    derive: () -> List<HrMinuteBucketEntity>,
): List<HrMinuteBucketEntity>? =
    try {
        derive()
    } catch (e: BpmHistogramFormatException) {
        logI(RELINK_TAG) { "Minute $bucketStartMs left unresolved: ${e.message}" }
        null
    }

private const val RELINK_TAG = "WarmTierRelinker"
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L

// Matches MinuteCoveragePublisher: bounded so a day-chunk's minute keys never approach SQLite's
// SQLITE_MAX_VARIABLE_NUMBER (999 on the oldest supported API levels).
private const val MINUTE_KEY_CHUNK = 500

private val BUCKET_ORDER: Comparator<HrMinuteBucketEntity> =
    compareBy({ it.bucketStartMs }, { it.recordType }, { it.sessionId }, { it.deviceName })
