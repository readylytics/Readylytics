package com.gregor.lauritz.healthdashboard.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity
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
class BloodPressureRepositoryImplTest {
    private lateinit var db: HealthDatabase
    private lateinit var dao: BloodPressureRecordDao
    private lateinit var repo: BloodPressureRepositoryImpl

    // Fixed timestamps (ms since epoch, synthetic values)
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

        dao = db.bloodPressureRecordDao()
        repo = BloodPressureRepositoryImpl(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- helpers ----

    private fun bp(
        id: String,
        timestampMs: Long,
        systolic: Int = 120,
        diastolic: Int = 80,
        device: String? = null,
    ) = BloodPressureRecordEntity(
        id = id,
        timestampMs = timestampMs,
        systolicMmHg = systolic,
        diastolicMmHg = diastolic,
        deviceName = device,
    )

    // ---- empty database ----

    @Test
    fun getByDateRange_emptyDatabase_returnsEmptyList() =
        runTest {
            val result = repo.getByDateRange(day1Start, day1End)
            assertTrue(result.isEmpty())
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
            dao.upsertAll(listOf(bp("r1", day1Mid)))

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(1, result.size)
            assertEquals("r1", result[0].id)
        }

    @Test
    fun getByDateRange_multipleRecords_returnsAllInRange() =
        runTest {
            dao.upsertAll(
                listOf(
                    bp("r1", day1Mid),
                    bp("r2", day1Mid + 1000),
                    bp("r3", day2Mid),
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
                    bp("before", day1Start - 1),
                    bp("inside", day2Mid),
                    bp("after", day3Mid),
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
                    bp("start", day2Start),
                    bp("end", day2End),
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
                    bp("old", day1Mid),
                    bp("mid", day2Mid),
                    bp("new", day3Mid),
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
                    bp("earlier", day2Mid - 10_000),
                    bp("later", day2Mid),
                    bp("other", day3Mid),
                ),
            )

            // dayEndMs is exclusive (< in the query), so pass day2End + 1
            val result = repo.getLatestByDate(day2Start, day2End + 1)

            assertNotNull(result)
            assertEquals("later", result!!.id)
        }

    @Test
    fun getLatestByDate_noRecordsInDay_returnsNull() =
        runTest {
            dao.upsertAll(listOf(bp("r1", day1Mid)))

            assertNull(repo.getLatestByDate(day2Start, day2End + 1))
        }

    // ---- upsert / update ----

    @Test
    fun upsertAll_updatesExistingRecord() =
        runTest {
            dao.upsertAll(listOf(bp("r1", day1Mid, systolic = 120, diastolic = 80)))
            dao.upsertAll(listOf(bp("r1", day1Mid, systolic = 130, diastolic = 85)))

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(1, result.size)
            assertEquals(130, result[0].systolicMmHg)
            assertEquals(85, result[0].diastolicMmHg)
        }

    @Test
    fun upsertAll_batchInsert_allRecordsPersisted() =
        runTest {
            val batch = (1..5).map { i -> bp("r$i", day1Start + i * 1000L) }
            dao.upsertAll(batch)

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(5, result.size)
        }

    // ---- delete ----

    @Test
    fun deleteBeforeTimestamp_removesOldRecords() =
        runTest {
            dao.upsertAll(
                listOf(
                    bp("old", day1Mid),
                    bp("recent", day3Mid),
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
            dao.upsertAll((1..3).map { i -> bp("r$i", day1Start + i * 1000L) })
            dao.deleteAll()

            assertEquals(0, dao.count())
        }

    // ---- observe (Flow) ----

    @Test
    fun observeByDateRange_emitsCurrentState() =
        runTest {
            dao.upsertAll(listOf(bp("r1", day1Mid)))

            val result = repo.observeByDateRange(day1Start, day1End).first()

            assertEquals(1, result.size)
            assertEquals("r1", result[0].id)
        }

    // ---- field values ----

    @Test
    fun storedFieldsMatchInserted() =
        runTest {
            val record = bp("unique1", day1Mid, systolic = 118, diastolic = 76, device = "WristBand")
            dao.upsertAll(listOf(record))

            val result = repo.getByDateRange(day1Start, day1End).first()

            assertEquals(118, result.systolicMmHg)
            assertEquals(76, result.diastolicMmHg)
            assertEquals("WristBand", result.deviceName)
            assertEquals(day1Mid, result.timestampMs)
        }

    // ---- ordering ----

    @Test
    fun getByDateRange_resultsOrderedByTimestampAsc() =
        runTest {
            dao.upsertAll(
                listOf(
                    bp("r3", day1Start + 3000),
                    bp("r1", day1Start + 1000),
                    bp("r2", day1Start + 2000),
                ),
            )

            val result = repo.getByDateRange(day1Start, day1End)

            assertEquals(listOf("r1", "r2", "r3"), result.map { it.id })
        }
}
