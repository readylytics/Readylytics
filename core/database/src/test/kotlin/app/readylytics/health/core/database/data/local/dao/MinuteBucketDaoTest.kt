package app.readylytics.health.core.database.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MinuteBucketDaoTest {
    private lateinit var database: HealthDatabase
    private lateinit var dao: MinuteBucketDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.minuteBucketDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertBuckets_insertsAndQueriesMinuteBuckets() =
        runBlocking {
            val bucket =
                HrMinuteBucketEntity(
                    bucketStartMs = 60000L,
                    bucketEndMs = 120000L,
                    minBpm = 60,
                    maxBpm = 80,
                    avgBpm = 70.0,
                    sampleCount = 60,
                    recordType = "RESTING",
                    deviceName = "Watch",
                )
            dao.upsertBuckets(listOf(bucket))
            val rows = dao.getMinuteBuckets(0L, 180000L)
            assertEquals(1, rows.size)
            assertEquals(70.0, rows[0].avgBpm, 0.01)
        }

    @Test
    fun deleteBeforeTimestamp_prunesOldBuckets() =
        runBlocking {
            val bucket1 = HrMinuteBucketEntity(60000L, 120000L, 60, 80, 70.0, 60, "RESTING", "", "")
            val bucket2 = HrMinuteBucketEntity(180000L, 240000L, 65, 85, 75.0, 60, "RESTING", "", "")
            dao.upsertBuckets(listOf(bucket1, bucket2))

            val deleted = dao.deleteBeforeTimestamp(150000L)
            assertEquals(1, deleted)
            val remaining = dao.getMinuteBuckets(0L, 300000L)
            assertEquals(1, remaining.size)
            assertEquals(180000 / 60000, remaining[0].bucketIndex)
        }

    @Test
    fun deleteBucketsNotMatchingDevice_deletesNonMatchingAndEmptyDevicesWithinRange() =
        runBlocking {
            val b1 = HrMinuteBucketEntity(60000L, 120000L, 60, 80, 70.0, 60, "RESTING", "", "Watch A")
            val b2 = HrMinuteBucketEntity(60000L, 120000L, 60, 80, 70.0, 60, "RESTING", "", "Watch B")
            val b3 = HrMinuteBucketEntity(60000L, 120000L, 60, 80, 70.0, 60, "RESTING", "", "")
            val b4 = HrMinuteBucketEntity(180000L, 240000L, 65, 85, 75.0, 60, "RESTING", "", "Watch A")
            dao.upsertBuckets(listOf(b1, b2, b3, b4))

            // Delete in range [0, 150000] for device != "Watch B"
            val deleted = dao.deleteBucketsNotMatchingDevice(0L, 150000L, "Watch B")
            assertEquals(2, deleted) // b1 and b3 deleted

            val remaining = dao.getBucketsForSession("RESTING", "")
            assertEquals(2, remaining.size)
            assertEquals("Watch B", remaining[0].deviceName)
            assertEquals("Watch A", remaining[1].deviceName)
        }

    @Test
    fun getDistinctDeviceNames_returnsNonEmptyDistinctDeviceNames() =
        runBlocking {
            val b1 = HrMinuteBucketEntity(60000L, 120000L, 60, 80, 70.0, 60, "RESTING", "", "Watch A")
            val b2 = HrMinuteBucketEntity(120000L, 180000L, 60, 80, 70.0, 60, "RESTING", "", "Watch B")
            val b3 = HrMinuteBucketEntity(180000L, 240000L, 60, 80, 70.0, 60, "RESTING", "", "Watch A")
            val b4 = HrMinuteBucketEntity(240000L, 300000L, 60, 80, 70.0, 60, "RESTING", "", "")
            dao.upsertBuckets(listOf(b1, b2, b3, b4))

            val devices = dao.getDistinctDeviceNames()
            assertEquals(listOf("Watch A", "Watch B").sorted(), devices.sorted())
        }
}
