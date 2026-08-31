package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
                minuteBucketDao = database.minuteBucketDao(),
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

            val touched = rollupManager.rollupExpiredHotTier(cutoffMs = 120_000L)

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

            rollupManager.rollupExpiredHotTier(cutoffMs = 60_000L)

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

            val touched = rollupManager.rollupExpiredHotTier(cutoffMs = 3 * dayMs)

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
            val touched = rollupManager.rollupExpiredHotTier(cutoffMs = 120_000L)
            assertNull(touched)
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
