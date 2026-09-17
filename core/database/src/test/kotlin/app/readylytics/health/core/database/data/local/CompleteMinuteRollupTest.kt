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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CompleteMinuteRollupTest {
    private lateinit var database: HealthDatabase
    private lateinit var rollupManager: DataRollupManager

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
        rollupManager = DataRollupManager(
            minuteBucketDao = database.minuteBucketDao(),
            minuteCoverageDao = database.minuteCoverageDao(),
            heartRateDao = database.heartRateDao(),
            transactionRunner = RoomTransactionRunner(database),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rollup leaves partial minutes intact`() = runBlocking {
        val heartRateDao = database.heartRateDao()
        val minuteBucketDao = database.minuteBucketDao()
        val sourceRecordDao = database.sourceRecordDao()

        val ref1 = sourceRecordDao.getOrCreateSourceRef("dev-1", "HEART_RATE", 0L)
        val ref2 = sourceRecordDao.getOrCreateSourceRef("dev-2", "HEART_RATE", 0L)

        heartRateDao.upsertAll(
            listOf(
                hr(ref1, 65_000L, 70),
                hr(ref2, 95_000L, 72),
                // sample exactly at the cutoff (120,000L) should not be included in the 60,000..120,000 bucket
                hr(ref1, 120_000L, 75)
            )
        )

        // Mid-minute cutoff (90,000ms). Cutoff becomes 60,000ms. 
        // We have samples at 65_000, 95_000, 120_000. All >= 60,000.
        // So no bucket created.
        val touched1 = rollupManager.rollupExpiredHotTier(90_000L)
        assertNull(touched1)
        assertEquals(3, heartRateDao.count())
        assertEquals(0, minuteBucketDao.getBucketsInTimeRange(0L, 200_000L).size)

        // Cutoff exactly at minute boundary (120,000ms).
        val touched2 = rollupManager.rollupExpiredHotTier(120_000L)
        assertEquals(LocalDate.of(1970, 1, 1), touched2?.start)
        assertEquals(LocalDate.of(1970, 1, 1), touched2?.endInclusive)

        // The two samples at 65_000 and 95_000 should be rolled up. The one at 120_000 should remain.
        assertEquals(1, heartRateDao.count())
        val remaining = heartRateDao.getPlausibleSamplesInRangeForRollup(0L, 200_000L)
        assertEquals(120_000L, remaining[0].timestampMs)

        val buckets = minuteBucketDao.getBucketsInTimeRange(0L, 200_000L)
        assertEquals(1, buckets.size)
        val bucket = buckets[0]
        assertEquals(60_000L, bucket.bucketStartMs)
        assertEquals(2, bucket.sampleCount)

        // Replay with same cutoff
        val touched3 = rollupManager.rollupExpiredHotTier(120_000L)
        assertNull(touched3)
        assertEquals(1, heartRateDao.count())
        assertEquals(1, minuteBucketDao.getBucketsInTimeRange(0L, 200_000L).size)
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
}
