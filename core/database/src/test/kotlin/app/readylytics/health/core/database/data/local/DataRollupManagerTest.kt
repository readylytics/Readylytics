package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DataRollupManagerTest {
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
        rollupManager =
            DataRollupManager(
                coordinator = TestHealthMutationCoordinator,
                minuteCoverageDao = database.minuteCoverageDao(),
                publisher = MinuteCoveragePublisher(database.minuteBucketDao(), database.minuteCoverageDao()),
                heartRateDao = database.heartRateDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rollupExpiredHotTierAggregatesIntoBucketsAndDeletesRawRows() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val sourceRecordDao = database.sourceRecordDao()
            val minuteBucketDao = database.minuteBucketDao()

            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-hr", "HEART_RATE", 0L)

            // Minute 0 (0..60_000): RESTING, 3 samples -> avg 70, min 60, max 80.
            heartRateDao.upsertAll(
                listOf(
                    hr(ref, 0L, 60, "RESTING", null),
                    hr(ref, 30_000L, 70, "RESTING", null),
                    hr(ref, 59_000L, 80, "RESTING", null),
                    // Minute 1 (60_000..120_000): SLEEP session s1, 2 samples -> avg 110.
                    hr(ref, 60_000L, 100, "SLEEP", "s1"),
                    hr(ref, 90_000L, 120, "SLEEP", "s1"),
                    // Minute 2 (>= cutoff): survives.
                    hr(ref, 120_000L, 90, "RESTING", null),
                ),
            )

            val touched = rollupManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(120_000L)), 120_000L)

            // All 5 rolled-up samples (ts 0..90_000ms) fall on the same 1970-01-01 UTC day.
            assertEquals(LocalDate.of(1970, 1, 1), touched?.start)
            assertEquals(LocalDate.of(1970, 1, 1), touched?.endInclusive)
            assertEquals(0, heartRateDao.countInRange(0L, 119_999L))
            assertEquals(1, heartRateDao.countInRange(120_000L, 180_000L))

            val buckets = minuteBucketDao.getMinuteBuckets(0L, 120_000L)
            assertEquals(2, buckets.size)
            // Weighted avg across both minutes: ((70*3) + (110*2)) / 5 = 86.0.
            assertEquals(86.0, (buckets[0].avgBpm * 3 + buckets[1].avgBpm * 2) / 5.0, 0.01)
        }

    // R2-DB-004 (review follow-up): confirms the seam between rollupExpiredHotTier and the new
    // percentile columns end-to-end, using MinuteBucketDao.getBucketsForSession -- unlike
    // getMinuteBuckets, that returns the full entity, including p5Bpm..p95Bpm. Same 12-sample
    // 50..61 fixture as MinuteBucketAggregatorTest, so the expected values are the same
    // hand-verified percentile math (see that test's header comment for the worked arithmetic).
    @Test
    fun rollupExpiredHotTierWritesPercentileSketchIntoBuckets() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val sourceRecordDao = database.sourceRecordDao()
            val minuteBucketDao = database.minuteBucketDao()

            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-hr-percentile", "HEART_RATE", 0L)
            heartRateDao.upsertAll(
                (0 until 12).map { i -> hr(ref, i * 5_000L, 50 + i, "SLEEP", "s-percentile") },
            )

            rollupManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(60_000L)), 60_000L)

            val buckets = minuteBucketDao.getBucketsForSession("SLEEP", "s-percentile")
            assertEquals(1, buckets.size)
            val bucket = buckets.single()
            assertEquals(50, bucket.minBpm)
            assertEquals(61, bucket.maxBpm)
            assertEquals(12, bucket.sampleCount)
            assertEquals(55.5, bucket.avgBpm, 0.01)
            assertEquals(51, bucket.p5Bpm)
            assertEquals(53, bucket.p25Bpm)
            assertEquals(56, bucket.p50Bpm)
            assertEquals(58, bucket.p75Bpm)
            assertEquals(60, bucket.p95Bpm)
        }

    // R2-DB-004 (review follow-up): the read/aggregate/delete pass is now chunked by UTC day
    // instead of processing everything before the cutoff in one transaction (unbounded-memory
    // risk on a large historical backlog). This proves day-chunking is transparent to callers:
    // raw samples spread across three distinct UTC days still roll up into exactly the buckets a
    // single unchunked pass would have produced, and every raw row before the cutoff is deleted.
    @Test
    fun rollupExpiredHotTierProducesIdenticalBucketsAcrossDayChunkBoundaries() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val sourceRecordDao = database.sourceRecordDao()
            val minuteBucketDao = database.minuteBucketDao()
            val dayMs = 24L * 60 * 60 * 1000

            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-hr-days", "HEART_RATE", 0L)
            // One sample in each of three separate UTC days, all in the same minute-of-day so
            // they'd collide into one bucket if day-chunking ever leaked across a day boundary.
            heartRateDao.upsertAll(
                listOf(
                    hr(ref, 0L, 60, "RESTING", null),
                    hr(ref, dayMs, 65, "RESTING", null),
                    hr(ref, 2 * dayMs, 70, "RESTING", null),
                ),
            )

            val touched = rollupManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(3 * dayMs)), 3 * dayMs)

            // Merged across three day-chunks: earliest sample's day .. latest sample's day.
            assertEquals(LocalDate.of(1970, 1, 1), touched?.start)
            assertEquals(LocalDate.of(1970, 1, 3), touched?.endInclusive)
            assertEquals(0, heartRateDao.count())
            val buckets = minuteBucketDao.getBucketsForSession("RESTING", "")
            assertEquals(3, buckets.size)
            assertEquals(setOf(60, 65, 70), buckets.map { it.minBpm }.toSet())
        }

    // R2-CACHE-001: an empty hot tier (nothing before the cutoff) must return null so
    // DataRollupWorker enqueues no recompute.
    @Test
    fun rollupExpiredHotTierReturnsNullWhenThereIsNothingToRollUp() =
        runBlocking {
            val touched = rollupManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(120_000L)), 120_000L)
            assertNull(touched)
        }

    // R2-PERF-002: verifies that rolling up multiple days opens a separate transaction per day chunk
    // to prevent single unbounded transactions over large historical backlogs.
    @Test
    fun `rolling up multiple days opens a separate transaction per day chunk`() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val sourceRecordDao = database.sourceRecordDao()
            val dayMs = 24L * 60 * 60 * 1000

            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-tx-test", "HEART_RATE", 0L)
            heartRateDao.upsertAll(
                listOf(
                    hr(ref, 10_000L, 60, "RESTING", null),
                    hr(ref, dayMs + 10_000L, 65, "RESTING", null),
                    hr(ref, 2 * dayMs + 10_000L, 70, "RESTING", null),
                ),
            )

            var transactionCount = 0
            val countingRunner =
                object : TransactionRunner {
                    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
                        transactionCount++
                        return RoomTransactionRunner(database).runInTransaction(block)
                    }
                }

            val manager =
                DataRollupManager(
                    coordinator = TestHealthMutationCoordinator,
                    minuteCoverageDao = database.minuteCoverageDao(),
                    publisher = MinuteCoveragePublisher(database.minuteBucketDao(), database.minuteCoverageDao()),
                    heartRateDao = database.heartRateDao(),
                    transactionRunner = countingRunner,
                )

            val touched = manager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(3 * dayMs)), 3 * dayMs)

            assertEquals(LocalDate.of(1970, 1, 1), touched?.start)
            assertEquals(LocalDate.of(1970, 1, 3), touched?.endInclusive)
            assertEquals(3, transactionCount)
            assertEquals(0, heartRateDao.count())
        }

    // R2-PERF-002: mid-pass crash test verifying earlier day-chunks remain committed and retry resumes idempotently.
    @Test
    fun `mid-pass interruption leaves committed days intact and resumes idempotently`() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val sourceRecordDao = database.sourceRecordDao()
            val minuteBucketDao = database.minuteBucketDao()
            val dayMs = 24L * 60 * 60 * 1000

            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-crash-test", "HEART_RATE", 0L)
            heartRateDao.upsertAll(
                listOf(
                    hr(ref, 10_000L, 60, "RESTING", null),
                    hr(ref, dayMs + 10_000L, 65, "RESTING", null),
                    hr(ref, 2 * dayMs + 10_000L, 70, "RESTING", null),
                ),
            )

            var attempts = 0
            val failingRunner =
                object : TransactionRunner {
                    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
                        attempts++
                        if (attempts == 2) {
                            error("Simulated crash during day 2")
                        }
                        return RoomTransactionRunner(database).runInTransaction(block)
                    }
                }

            val crashingManager =
                DataRollupManager(
                    coordinator = TestHealthMutationCoordinator,
                    minuteCoverageDao = database.minuteCoverageDao(),
                    publisher = MinuteCoveragePublisher(database.minuteBucketDao(), database.minuteCoverageDao()),
                    heartRateDao = database.heartRateDao(),
                    transactionRunner = failingRunner,
                )

            try {
                crashingManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(3 * dayMs)), 3 * dayMs)
            } catch (_: IllegalStateException) {
                // Expected crash
            }

            // Day 1 committed; Day 2 & 3 still present in hot tier
            assertEquals(2, heartRateDao.count())
            assertEquals(1, minuteBucketDao.getBucketsForSession("RESTING", "").size)

            // Resume with normal manager
            val resumedTouched = rollupManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(3 * dayMs)), 3 * dayMs)
            assertEquals(LocalDate.of(1970, 1, 2), resumedTouched?.start)
            assertEquals(LocalDate.of(1970, 1, 3), resumedTouched?.endInclusive)
            assertEquals(0, heartRateDao.count())
            assertEquals(3, minuteBucketDao.getBucketsForSession("RESTING", "").size)
        }

    // PERF-003 (Task 8): the core correctness contract of streaming the rollup -- bucket output
    // (avgBpm, sampleCount, min/max, p5..p95) must be byte-identical whether a day's samples fit in
    // one keyset page/group or are forced across many. Two fresh databases are seeded with the
    // identical dense-3-minute fixture; one rolls up with a page/group size that comfortably fits
    // the whole day in a single page and group, the other with a page size small enough to force
    // several keyset pages and a group-minute-budget of 1 (one group per minute). Since
    // `aggregateIntoMinuteBuckets` sorts each minute's own BPM values before computing avg/
    // percentiles, the result must depend only on which samples share a minute, never on how many
    // pages/groups the run was split into.
    @Test
    fun `rollup produces byte-identical buckets whether the day fits one page or is split across many`() =
        runBlocking {
            fun freshDatabase() =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        HealthDatabase::class.java,
                    ).allowMainThreadQueries()
                    .build()

            fun manager(db: HealthDatabase) =
                DataRollupManager(
                    coordinator = TestHealthMutationCoordinator,
                    minuteCoverageDao = db.minuteCoverageDao(),
                    heartRateDao = db.heartRateDao(),
                    publisher = MinuteCoveragePublisher(db.minuteBucketDao(), db.minuteCoverageDao()),
                    transactionRunner = RoomTransactionRunner(db),
                )

            suspend fun seedDenseDay(db: HealthDatabase) {
                val ref = db.sourceRecordDao().getOrCreateSourceRef("dense", "HEART_RATE", 0L)
                val samples =
                    (0 until 3).flatMap { minute ->
                        (0 until 21).map { i ->
                            hr(
                                ref,
                                minute * 60_000L + i * 2_500L,
                                50 + (minute * 7 + i * 3) % 41,
                                "RESTING",
                                null,
                            )
                        }
                    }
                db.heartRateDao().upsertAll(samples)
            }

            val singlePageDb = freshDatabase()
            val manyPagesDb = freshDatabase()
            seedDenseDay(singlePageDb)
            seedDenseDay(manyPagesDb)

            // Single page, single group: the whole 3-minute/63-sample day fits well under both defaults.
            manager(singlePageDb).rollupExpiredHotTier(
                cutoffMs = 3 * 60_000L,
                pageSize = MinuteRollupStreamer.SAMPLE_PAGE_SIZE,
                groupMinuteBudget = MinuteRollupStreamer.GROUP_MINUTE_BUDGET,
            )
            // Forced multi-page (7 rows/page against 63 total) and one group per minute.
            manager(manyPagesDb).rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(3 * 60_000L)), 3 * 60_000L, pageSize = 7, groupMinuteBudget = 1)

            val singlePageBuckets =
                singlePageDb.minuteBucketDao().getBucketsInTimeRange(0L, 3 * 60_000L).sortedBy { it.bucketStartMs }
            val manyPagesBuckets =
                manyPagesDb.minuteBucketDao().getBucketsInTimeRange(0L, 3 * 60_000L).sortedBy { it.bucketStartMs }

            assertEquals(3, singlePageBuckets.size)
            assertEquals(singlePageBuckets.map { it.bucketStartMs }, manyPagesBuckets.map { it.bucketStartMs })
            singlePageBuckets.zip(manyPagesBuckets).forEach { (single, many) ->
                assertEquals("avgBpm must be byte-identical", single.avgBpm, many.avgBpm, 0.0)
                assertEquals(single.sampleCount, many.sampleCount)
                assertEquals(single.minBpm, many.minBpm)
                assertEquals(single.maxBpm, many.maxBpm)
                assertEquals(single.p5Bpm, many.p5Bpm)
                assertEquals(single.p25Bpm, many.p25Bpm)
                assertEquals(single.p50Bpm, many.p50Bpm)
                assertEquals(single.p75Bpm, many.p75Bpm)
                assertEquals(single.p95Bpm, many.p95Bpm)
            }

            singlePageDb.close()
            manyPagesDb.close()
        }

    // PERF-003 (Task 8): a minute whose only raw rows are implausible (outside 30..230 bpm) never
    // appears in any streamed group -- the old single-pass rollupDayChunk still swept it via an
    // unconditional whole-day delete. The new per-group deletes alone would leak it forever, so
    // rollupDayChunk's conditional day-scoped backstop sweep must still catch it.
    @Test
    fun `rollup sweeps implausible-only raw rows even though they never form a group`() =
        runBlocking {
            val heartRateDao = database.heartRateDao()
            val sourceRecordDao = database.sourceRecordDao()

            val ref = sourceRecordDao.getOrCreateSourceRef("uuid-implausible-only", "HEART_RATE", 0L)
            heartRateDao.upsertAll(
                listOf(
                    hr(ref, 5_000L, 250, "RESTING", null),
                    hr(ref, 10_000L, 20, "RESTING", null),
                ),
            )

            val touched = rollupManager.rollupExpiredHotTier(app.readylytics.health.core.model.domain.sync.ScoringRunContext.capture(app.readylytics.health.core.model.domain.preferences.UserPreferences(), java.time.Instant.ofEpochMilli(60_000L)), 60_000L)

            assertNull("an implausible-only day publishes nothing", touched)
            assertEquals(0, heartRateDao.count())
        }

    private fun hr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
        recordType: String,
        sessionId: String?,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = recordType,
        sessionId = sessionId,
    )
}
