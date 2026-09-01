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
    fun getBucketsInTimeRange_returnsBucketsOverlappingWindowRegardlessOfRecordType() =
        runBlocking {
            dao.upsertBuckets(
                listOf(
                    bucketFixture(
                        recordType = "SLEEP", sessionId = "s1",
                        bucketStartMs = 1_000L, bucketEndMs = 60_999L,
                    ),
                    bucketFixture(
                        recordType = "RESTING", sessionId = "",
                        bucketStartMs = 61_000L, bucketEndMs = 120_999L,
                    ),
                    bucketFixture(
                        recordType = "EXERCISE", sessionId = "w1",
                        bucketStartMs = 500_000L, bucketEndMs = 560_999L,
                    ),
                ),
            )

            val result = dao.getBucketsInTimeRange(0L, 200_000L)

            assertEquals(2, result.size)
        }

    private fun bucketFixture(
        recordType: String,
        sessionId: String,
        bucketStartMs: Long,
        bucketEndMs: Long,
        minBpm: Int = 60,
        maxBpm: Int = 80,
        avgBpm: Double = 70.0,
        sampleCount: Int = 60,
        deviceName: String = "",
    ): HrMinuteBucketEntity =
        HrMinuteBucketEntity(
            bucketStartMs = bucketStartMs,
            bucketEndMs = bucketEndMs,
            minBpm = minBpm,
            maxBpm = maxBpm,
            avgBpm = avgBpm,
            sampleCount = sampleCount,
            recordType = recordType,
            sessionId = sessionId,
            deviceName = deviceName,
        )
}
