package com.gregor.lauritz.healthdashboard.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.dao.WeightRecordDao
import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeightRepositoryImplTest {
    private lateinit var db: HealthDatabase
    private lateinit var dao: WeightRecordDao
    private lateinit var repo: WeightRepositoryImpl

    // Synthetic timestamps
    private val day1Start = 1_000_000L
    private val day1Mid = 1_050_000L
    private val day1End = 1_099_999L
    private val day2Start = 1_100_000L
    private val day2Mid = 1_150_000L
    private val day2End = 1_199_999L
    private val day3Start = 1_200_000L
    private val day3Mid = 1_250_000L

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()

        dao = db.weightRecordDao()
        repo = WeightRepositoryImpl(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- helpers ----

    private fun weight(
        id: String,
        timestampMs: Long,
        weightKg: Float = 70f,
        device: String? = null,
    ) = WeightRecordEntity(
        id = id,
        timestampMs = timestampMs,
        weightKg = weightKg,
        deviceName = device,
    )

    // ---- empty database ----

    @Test
    fun getByDateRange_emptyDatabase_returnsEmptyList() =
        runTest {
            assertTrue(repo.getByDateRange(day1Start, day1End).isEmpty())
        }

    @Test
    fun getLatest_emptyDatabase_returnsNull() =
        runTest {
            assertNull(repo.getLatest())
        }

    @Test
    fun getLatestByDate_emptyDatabase_returnsNull() =
        runTest {
            assertNull(repo.getLatestByDate(day1Start, day1End))
        }

    // ---- insert + read ----

    @Test
    fun getByDateRange_singleRecord_returnsThatRecord() =
        runTest {
            dao.upsertAll(listOf(weight("w1", day1Mid)))

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(1, result.size)
            assertEquals("w1", result[0].id)
        }

    @Test
    fun getByDateRange_multipleRecordsInRange_returnsAll() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("w1", day1Mid),
                    weight("w2", day1Mid + 1000),
                    weight("w3", day2Mid),
                ),
            )

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(2, result.size)
        }

    @Test
    fun getByDateRange_recordsOutsideRange_excluded() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("before", day1Start - 1),
                    weight("inside", day2Mid),
                    weight("after", day3Mid),
                ),
            )

            val result = repo.getByDateRange(day2Start, day2End)

            assertEquals(1, result.size)
            assertEquals("inside", result[0].id)
        }

    @Test
    fun getByDateRange_boundaryTimestampsIncluded() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("start", day2Start),
                    weight("end", day2End),
                ),
            )

            val result = repo.getByDateRange(day2Start, day2End)

            assertEquals(2, result.size)
        }

    @Test
    fun getLatest_returnsNewestRecord() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("old", day1Mid),
                    weight("mid", day2Mid),
                    weight("new", day3Mid),
                ),
            )

            val result = repo.getLatest()

            assertNotNull(result)
            assertEquals("new", result!!.id)
        }

    @Test
    fun getLatestByDate_returnsLatestWithinDay() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("earlier", day2Mid - 10_000),
                    weight("later", day2Mid),
                    weight("other", day3Mid),
                ),
            )

            // dayEndMs is exclusive in the DAO query (timestampMs < dayEndMs)
            val result = repo.getLatestByDate(day2Start, day2End + 1)

            assertNotNull(result)
            assertEquals("later", result!!.id)
        }

    @Test
    fun getLatestByDate_noRecordsInDay_returnsNull() =
        runTest {
            dao.upsertAll(listOf(weight("w1", day1Mid)))

            assertNull(repo.getLatestByDate(day2Start, day2End + 1))
        }

    // ---- upsert / update ----

    @Test
    fun upsertAll_updatesExistingRecord() =
        runTest {
            dao.upsertAll(listOf(weight("w1", day1Mid, weightKg = 70f)))
            dao.upsertAll(listOf(weight("w1", day1Mid, weightKg = 71.5f)))

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(1, result.size)
            assertEquals(71.5f, result[0].weightKg)
        }

    @Test
    fun upsertAll_batchInsert_allRecordsPersisted() =
        runTest {
            val batch = (1..5).map { i -> weight("w$i", day1Start + i * 1000L, weightKg = 60f + i) }
            dao.upsertAll(batch)

            assertEquals(5, dao.count())
        }

    // ---- delete ----

    @Test
    fun deleteBeforeTimestamp_removesOldRecords() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("old", day1Mid),
                    weight("recent", day3Mid),
                ),
            )

            dao.deleteBeforeTimestamp(day2Start)

            val result = repo.getByDateRange(day1Start, day3Mid + 1)
            assertEquals(1, result.size)
            assertEquals("recent", result[0].id)
        }

    @Test
    fun deleteAll_clearsTable() =
        runTest {
            dao.upsertAll((1..3).map { i -> weight("w$i", day1Start + i * 1000L) })
            dao.deleteAll()

            assertEquals(0, dao.count())
        }

    // ---- observe (Flow) ----

    @Test
    fun observeByDateRange_emitsCurrentState() =
        runTest {
            dao.upsertAll(listOf(weight("w1", day1Mid)))

            val result = repo.observeByDateRange(day1Start, day1End).first()

            assertEquals(1, result.size)
            assertEquals("w1", result[0].id)
        }

    // ---- field values ----

    @Test
    fun storedFieldsMatchInserted() =
        runTest {
            val record = weight("unique1", day1Mid, weightKg = 68.4f, device = "SmartScale")
            dao.upsertAll(listOf(record))

            val result = repo.getByDateRange(day1Start, day1End).first()

            assertEquals(68.4f, result.weightKg)
            assertEquals("SmartScale", result.deviceName)
            assertEquals(day1Mid, result.timestampMs)
        }

    // ---- ordering ----

    @Test
    fun getByDateRange_resultsOrderedByTimestampAsc() =
        runTest {
            dao.upsertAll(
                listOf(
                    weight("w3", day1Start + 3000),
                    weight("w1", day1Start + 1000),
                    weight("w2", day1Start + 2000),
                ),
            )

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(listOf("w1", "w2", "w3"), result.map { it.id })
        }
}
