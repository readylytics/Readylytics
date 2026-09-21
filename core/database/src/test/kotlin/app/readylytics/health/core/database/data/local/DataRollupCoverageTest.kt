package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * WP-17/OD-1 coverage behaviour of the hot→warm rollup: what it publishes, what it refuses to
 * touch, and that no minute can end up carrying two generations or two qualities at once.
 */
@RunWith(RobolectricTestRunner::class)
class DataRollupCoverageTest {
    private lateinit var database: HealthDatabase
    private lateinit var rollupManager: DataRollupManager

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runBlocking { database.healthMutationStateDao().getOrCreate() }
        rollupManager =
            DataRollupManager(
                minuteCoverageDao = database.minuteCoverageDao(),
                heartRateDao = database.heartRateDao(),
                publisher =
                    MinuteCoveragePublisher(
                        minuteBucketDao = database.minuteBucketDao(),
                        minuteCoverageDao = database.minuteCoverageDao(),
                        dirtyRangeDao = database.dirtyRangeDao(),
                        healthMutationStateDao = database.healthMutationStateDao(),
                    ),
                transactionRunner = RoomTransactionRunner(database),
                dirtyRangeDao = database.dirtyRangeDao(),
                healthMutationStateDao = database.healthMutationStateDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rollup publishes source-backed coverage and one contribution per source-minute`() =
        runBlocking {
            val refA = seedSource("src-a")
            val refB = seedSource("src-b")
            database.heartRateDao().upsertAll(
                listOf(
                    hr(refA, 1_000L, 60),
                    hr(refA, 30_000L, 62),
                    hr(refB, 40_000L, 90),
                ),
            )

            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single()
            assertEquals(TIER_WARM, coverage.tier)
            assertEquals(QUALITY_SOURCE_BACKED, coverage.quality)
            assertTrue(coverage.visibleGeneration > 0L)

            val contributions = database.minuteCoverageDao().getContributionsForMinute(0L)
            assertEquals(2, contributions.size)
            assertEquals(setOf(refA, refB), contributions.map { it.sourceRecordRef }.toSet())
            assertEquals(setOf(coverage.visibleGeneration), contributions.map { it.generation }.toSet())
            // Histogram evidence is the exact frequency table of that source's plausible samples.
            val histogramA = BpmHistogram.decode(contributions.single { it.sourceRecordRef == refA }.bpmHistogram)
            assertEquals(mapOf(60 to 1, 62 to 1), histogramA.bins)
            assertEquals(0, database.heartRateDao().count())
        }

    // C4: an ordinary rollup is not the authorized complete refresh OD-1 requires before legacy
    // coverage may be replaced. The legacy minute keeps its coverage AND its legacy bucket, its raw
    // samples stay as quarantine evidence, and no source-backed rows are mixed into it.
    @Test
    fun `rollup quarantines a minute that already carries legacy coverage`() =
        runBlocking {
            val ref = seedSource("src-a")
            seedLegacyMinute(bucketStartMs = 0L, deviceName = "old-device")
            database.heartRateDao().upsertAll(
                listOf(
                    hr(ref, 1_000L, 60),
                    hr(ref, 65_000L, 70),
                ),
            )

            rollupManager.rollupExpiredHotTier(2 * MINUTE_MS)

            val legacy = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single()
            assertEquals("LEGACY_WARM", legacy.tier)
            assertEquals(QUALITY_LEGACY_UNKNOWN, legacy.quality)
            assertEquals(0L, legacy.visibleGeneration)
            assertEquals(0, database.minuteCoverageDao().getContributionsForMinute(0L).size)

            val legacyBuckets = database.minuteBucketDao().getBucketsInTimeRange(0L, MINUTE_MS - 1)
            assertEquals(1, legacyBuckets.size)
            assertEquals("old-device", legacyBuckets.single().deviceName)
            assertEquals(0L, legacyBuckets.single().generation)

            // Raw evidence for the quarantined minute survives; the clean minute was consumed.
            assertEquals(1, database.heartRateDao().count())
            assertEquals(1, database.heartRateDao().countInRange(0L, MINUTE_MS - 1))

            val published = database.minuteCoverageDao().getCoverageInRange(MINUTE_MS, 2 * MINUTE_MS).single()
            assertEquals(QUALITY_SOURCE_BACKED, published.quality)
            assertEquals(1, database.minuteCoverageDao().getContributionsForMinute(MINUTE_MS).size)
        }

    // C4: the invariant the quarantine rule exists to protect -- no minute is ever visible with a
    // mix of generations or a mix of qualities.
    @Test
    fun `no minute ends up with mixed generation or mixed quality visible rows`() =
        runBlocking {
            val ref = seedSource("src-a")
            seedLegacyMinute(bucketStartMs = 0L, deviceName = "old-device")
            database.heartRateDao().upsertAll(
                listOf(
                    hr(ref, 1_000L, 60),
                    hr(ref, 65_000L, 70),
                    hr(ref, 125_000L, 80),
                ),
            )

            rollupManager.rollupExpiredHotTier(3 * MINUTE_MS)

            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, 3 * MINUTE_MS)
            assertEquals(3, coverage.size)
            coverage.forEach { row ->
                val buckets =
                    database
                        .minuteBucketDao()
                        .getBucketsInTimeRange(row.bucketStartMs, row.bucketStartMs)
                        .filter { it.bucketStartMs == row.bucketStartMs }
                assertTrue("Every visible minute must have at least one bucket", buckets.isNotEmpty())
                assertEquals(
                    "Minute ${row.bucketStartMs} carries mixed bucket generations",
                    setOf(row.visibleGeneration),
                    buckets.map { it.generation }.toSet(),
                )
                val contributionGenerations =
                    database
                        .minuteCoverageDao()
                        .getContributionsForMinute(row.bucketStartMs)
                        .map { it.generation }
                        .toSet()
                if (row.quality == QUALITY_SOURCE_BACKED) {
                    assertEquals(setOf(row.visibleGeneration), contributionGenerations)
                } else {
                    assertEquals(emptySet<Long>(), contributionGenerations)
                }
            }
        }

    // C5: re-rolling the same minute (late-arriving raw samples) supersedes the previous
    // generation's contributions instead of appending a second generation's rows.
    //
    // Known limitation, asserted here so it stays explicit: the earlier generation's raw rows were
    // already consumed, so the republished minute is re-derived from the still-present raw samples
    // only -- the superseded histogram's evidence is discarded, exactly as the pre-existing
    // `upsertBuckets` REPLACE already discarded the superseded bucket. Recovering the full minute
    // requires an authorized complete refresh (staged complete source payload), not a rollup.
    @Test
    fun `re-rolling the same minute leaves exactly one generation of contributions`() =
        runBlocking {
            val ref = seedSource("src-a")
            database.heartRateDao().upsertAll(listOf(hr(ref, 1_000L, 60)))
            rollupManager.rollupExpiredHotTier(MINUTE_MS)
            val firstGeneration =
                database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single().visibleGeneration

            database.heartRateDao().upsertAll(listOf(hr(ref, 2_000L, 66)))
            rollupManager.rollupExpiredHotTier(MINUTE_MS)

            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single()
            assertTrue(coverage.visibleGeneration > firstGeneration)

            val contributions = database.minuteCoverageDao().getContributionsForMinute(0L)
            assertEquals(1, contributions.size)
            assertEquals(coverage.visibleGeneration, contributions.single().generation)
            assertEquals(
                mapOf(66 to 1),
                BpmHistogram.decode(contributions.single().bpmHistogram).bins,
            )

            val buckets = database.minuteBucketDao().getBucketsInTimeRange(0L, 0L)
            assertEquals(1, buckets.size)
            assertEquals(coverage.visibleGeneration, buckets.single().generation)
            assertEquals(1, buckets.single().sampleCount)
        }

    // I7: contributions reference `health_source_records` with ON DELETE RESTRICT, and the HC
    // delta/deletion reconciliation paths delete source rows directly. Deleting a source whose
    // minutes were already rolled up must converge (drop only that source's contributions), not
    // raise SQLiteConstraintException and abort the ingestion transaction.
    @Test
    fun `deleting a rolled-up source removes only its own contributions`() =
        runBlocking {
            val refA = seedSource("src-a")
            seedSource("src-b")
            database.heartRateDao().upsertAll(
                listOf(
                    hr(refA, 1_000L, 60),
                    hr(database.sourceRecordDao().getSourceRef("src-b")!!, 2_000L, 90),
                ),
            )
            rollupManager.rollupExpiredHotTier(MINUTE_MS)
            assertEquals(2, database.minuteCoverageDao().getContributionsForMinute(0L).size)

            val deleted = database.sourceRecordDao().deleteBySourceRecordId("src-a")

            assertEquals(1, deleted)
            val remaining = database.minuteCoverageDao().getContributionsForMinute(0L)
            assertEquals(1, remaining.size)
            assertEquals("src-b", database.sourceRecordDao().getAll().single().sourceRecordId)
            assertEquals(remaining.single().sourceRecordRef, database.sourceRecordDao().getSourceRef("src-b"))
            // The already-visible projection is intentionally left in place (see SourceRecordDao).
            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, MINUTE_MS).single()
            assertEquals(QUALITY_SOURCE_BACKED, coverage.quality)
        }

    // Task 8 (PERF-003): `nextGeneration()` is captured once PER GROUP, not once per day, so a
    // concurrent source mutation between one group's generation-capture and its own publish
    // transaction aborts only that group -- groups already committed earlier in the same pass (even
    // the same day chunk) stay published, and the pass stops cleanly (no crash) rather than
    // continuing to chunk against a moving generation.
    @Test
    fun `a generation conflict on one group stops the pass without losing earlier groups`() =
        runBlocking {
            val ref = seedSource("src-a")
            // Two samples a minute apart so groupMinuteBudget = 1 forces two separate publishGroup
            // calls (two transactions) within the same day chunk.
            database.heartRateDao().upsertAll(
                listOf(
                    hr(ref, 1_000L, 60),
                    hr(ref, 65_000L, 70),
                ),
            )

            var transactionCount = 0
            val conflictInjectingRunner =
                object : TransactionRunner {
                    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
                        transactionCount++
                        if (transactionCount == 2) {
                            // Simulates another writer mutating sources, in its own transaction,
                            // between the second group's generation-capture (outside any
                            // transaction) and this transaction's own publish.
                            database.healthMutationStateDao().incrementGeneration()
                        }
                        return RoomTransactionRunner(database).runInTransaction(block)
                    }
                }

            val manager =
                DataRollupManager(
                    minuteCoverageDao = database.minuteCoverageDao(),
                    heartRateDao = database.heartRateDao(),
                    publisher =
                        MinuteCoveragePublisher(
                            minuteBucketDao = database.minuteBucketDao(),
                            minuteCoverageDao = database.minuteCoverageDao(),
                            dirtyRangeDao = database.dirtyRangeDao(),
                            healthMutationStateDao = database.healthMutationStateDao(),
                        ),
                    transactionRunner = conflictInjectingRunner,
                    dirtyRangeDao = database.dirtyRangeDao(),
                    healthMutationStateDao = database.healthMutationStateDao(),
                )

            // Must NOT throw -- the conflict is caught inside rollupDayChunk and the pass stops
            // cleanly, returning whatever was already published.
            val touched = manager.rollupExpiredHotTier(cutoffMs = 2 * MINUTE_MS, groupMinuteBudget = 1)

            // Group 1 (minute 0) committed before the injected conflict.
            val coverage = database.minuteCoverageDao().getCoverageInRange(0L, 2 * MINUTE_MS)
            assertEquals(1, coverage.size)
            assertEquals(0L, coverage.single().bucketStartMs)
            assertEquals(LocalDate.of(1970, 1, 1), touched?.start)
            assertEquals(LocalDate.of(1970, 1, 1), touched?.endInclusive)

            // Group 2 (minute 1) was aborted -- its raw sample survives untouched for the next
            // scheduled rollup to retry idempotently.
            assertEquals(1, database.heartRateDao().count())
            val remainingRaw = database.heartRateDao().getPlausibleSamplesInRangeForRollup(0L, 2 * MINUTE_MS)
            assertEquals(1, remainingRaw.size)
            assertEquals(65_000L, remainingRaw.single().timestampMs)
        }

    private suspend fun seedSource(id: String): Long =
        database.sourceRecordDao().getOrCreateSourceRef(id, "HEART_RATE", 0L)

    /** A pre-v22 minute: an approximate warm bucket plus legacy coverage, at generation 0. */
    private suspend fun seedLegacyMinute(
        bucketStartMs: Long,
        deviceName: String,
    ) {
        database.minuteBucketDao().upsertBuckets(
            listOf(
                HrMinuteBucketEntity(
                    bucketStartMs = bucketStartMs,
                    bucketEndMs = bucketStartMs + MINUTE_MS,
                    minBpm = 50,
                    maxBpm = 70,
                    avgBpm = 60.0,
                    sampleCount = 10,
                    recordType = "RESTING",
                    deviceName = deviceName,
                ),
            ),
        )
        database.minuteCoverageDao().upsertCoverage(
            listOf(
                MinuteCoverageEntity(
                    bucketStartMs = bucketStartMs,
                    visibleGeneration = 0L,
                    tier = "LEGACY_WARM",
                    quality = QUALITY_LEGACY_UNKNOWN,
                    sourceSelectionId = null,
                ),
            ),
        )
    }

    private fun hr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "RESTING",
        sessionId = null,
    )

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
