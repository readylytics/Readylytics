package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PERF-005/WP-23: verifies `HeartRateDao.observeAggregateByTimeRange`'s `HAVING COUNT(*) > 0`
 * clause returns no row (mapped by Room to `null`) for an empty range, rather than one row of
 * NULLs that would crash mapping to the non-null [app.readylytics.health.domain.model.HrRangeAggregate] fields.
 */
@RunWith(AndroidJUnit4::class)
class HeartRateRangeAggregateQueryTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao

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

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        database.sourceRecordDao().insertAll(
            refs.map { ref ->
                HealthSourceRecordEntity(
                    id = ref,
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )
    }

    @Test
    fun `empty range yields null instead of a row of NULLs`() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            val aggregate = heartRateDao.observeAggregateByTimeRange(0L, 1_000L).first()

            assertNull(aggregate)
        }

    @Test
    fun `non-empty range yields correct min max avg count`() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 0L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = 100L,
                        beatsPerMinute = 80,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 3L,
                        timestampMs = 200L,
                        beatsPerMinute = 100,
                        recordType = "RESTING",
                    ),
                ),
            )

            val aggregate = heartRateDao.observeAggregateByTimeRange(0L, 1_000L).first()

            requireNotNull(aggregate)
            assertEquals(60, aggregate.minBpm)
            assertEquals(100, aggregate.maxBpm)
            assertEquals(80.0, aggregate.avgBpm, 0.001)
            assertEquals(3, aggregate.sampleCount)
        }

    @Test
    fun `range bound is exclusive at endMs`() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1_000L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    ),
                ),
            )

            val aggregate = heartRateDao.observeAggregateByTimeRange(0L, 1_000L).first()

            assertNull(aggregate)
        }
}
