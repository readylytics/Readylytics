package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PERF-006/WP-21: verifies `HeartRateDao.getMinuteBuckets`'s SQL-side bucket-index math,
 * plausibility filter, and per-minute averaging reproduce what the calculator's former Kotlin-side
 * bucketing did -- the equivalent coverage `EverydayHeartRateLoadCalculatorTest`'s
 * "sample exactly at bucket boundary" case had before the calculator stopped owning bucketing.
 */
@RunWith(AndroidJUnit4::class)
class HeartRateMinuteBucketQueryTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao

    private val dayStartMs = 0L
    private val dayEndMs = 24L * 60L * 60_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `sample exactly at bucket boundary falls into the later bucket`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        id = "hr1",
                        timestampMs = 60_000L,
                        beatsPerMinute = 130,
                        recordType = "RESTING",
                    ),
                ),
            )

            val buckets = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

            assertEquals(1, buckets.size)
            assertEquals(1, buckets[0].bucketIndex)
            assertEquals(130.0, buckets[0].avgBpm, 0.001)
        }

    @Test
    fun `implausible samples never produce a bucket row`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(id = "hr1", timestampMs = 0L, beatsPerMinute = 20, recordType = "RESTING"),
                    HeartRateRecordEntity(
                        id = "hr2",
                        timestampMs = 30_000L,
                        beatsPerMinute = 250,
                        recordType = "RESTING",
                    ),
                ),
            )

            val buckets = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

            assertTrue(buckets.isEmpty())
        }

    @Test
    fun `multiple samples in the same minute are averaged into one bucket row`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(id = "hr1", timestampMs = 0L, beatsPerMinute = 120, recordType = "RESTING"),
                    HeartRateRecordEntity(
                        id = "hr2",
                        timestampMs = 30_000L,
                        beatsPerMinute = 140,
                        recordType = "RESTING",
                    ),
                ),
            )

            val buckets = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

            assertEquals(1, buckets.size)
            assertEquals(0, buckets[0].bucketIndex)
            assertEquals(130.0, buckets[0].avgBpm, 0.001)
            assertEquals(2, buckets[0].sampleCount)
        }

    @Test
    fun `implausible sample in a minute is excluded from that minute's average`() =
        runTest {
            // One plausible (130) and one implausible (250) sample in minute 0: the average must
            // reflect only the plausible sample, not both.
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(id = "hr1", timestampMs = 0L, beatsPerMinute = 130, recordType = "RESTING"),
                    HeartRateRecordEntity(
                        id = "hr2",
                        timestampMs = 30_000L,
                        beatsPerMinute = 250,
                        recordType = "RESTING",
                    ),
                ),
            )

            val buckets = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

            assertEquals(1, buckets.size)
            assertEquals(130.0, buckets[0].avgBpm, 0.001)
            assertEquals(1, buckets[0].sampleCount)
        }

    @Test
    fun `buckets are returned in ascending bucketIndex order`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        id = "hr1",
                        timestampMs = 180_000L,
                        beatsPerMinute = 100,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(id = "hr2", timestampMs = 0L, beatsPerMinute = 100, recordType = "RESTING"),
                    HeartRateRecordEntity(
                        id = "hr3",
                        timestampMs = 60_000L,
                        beatsPerMinute = 100,
                        recordType = "RESTING",
                    ),
                ),
            )

            val buckets = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

            assertEquals(listOf(0, 1, 3), buckets.map { it.bucketIndex })
        }

    @Test
    fun `dayEndMs is exclusive`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        id = "hr1",
                        timestampMs = dayEndMs,
                        beatsPerMinute = 100,
                        recordType = "RESTING",
                    ),
                ),
            )

            val buckets = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

            assertTrue(buckets.isEmpty())
        }
}
