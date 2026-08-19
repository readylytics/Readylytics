package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

            val deleted = rollupManager.rollupExpiredHotTier(cutoffMs = 120_000L)

            assertEquals(5, deleted)
            assertEquals(0, heartRateDao.countInRange(0L, 119_999L))
            assertEquals(1, heartRateDao.countInRange(120_000L, 180_000L))

            val buckets = minuteBucketDao.getMinuteBuckets(0L, 120_000L)
            assertEquals(2, buckets.size)
            // Weighted avg across both minutes: ((70*3) + (110*2)) / 5 = 86.0.
            assertEquals(86.0, (buckets[0].avgBpm * 3 + buckets[1].avgBpm * 2) / 5.0, 0.01)
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
