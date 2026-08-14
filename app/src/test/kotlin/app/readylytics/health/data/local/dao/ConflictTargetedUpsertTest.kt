package app.readylytics.health.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the conflict-targeted UPSERT that replaced `@Insert(onConflict = REPLACE)`
 * in [HeartRateDao] / [HrvDao] (HEAVY_DATA_SYNC_STABILITY_PLAN Step 8). Verifies on a real
 * in-memory Room DB that re-ingesting the same natural key (sourceRecordId, timestampMs) updates
 * mutable columns in place with a stable `rowId` instead of delete+reinsert rotating it.
 */
@RunWith(AndroidJUnit4::class)
class ConflictTargetedUpsertTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `identical heart rate re-ingest keeps rowId stable and does not duplicate`() =
        runTest {
            heartRateDao.upsertAll(listOf(HeartRateRecordEntity("id-1", 1000L, 60, "SLEEP")))

            val firstRowId = heartRateDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId
            heartRateDao.upsertAll(listOf(HeartRateRecordEntity("id-1", 1000L, 60, "SLEEP")))

            val rows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(firstRowId, rows.single().rowId)
        }

    @Test
    fun `re-tagged heart rate record updates session, record type, and device name in place`() =
        runTest {
            heartRateDao.upsertAll(listOf(HeartRateRecordEntity("id-1", 1000L, 60, "RESTING", deviceName = "Watch 1")))
            val originalRowId = heartRateDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId

            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        "id-1",
                        1000L,
                        60,
                        "SLEEP",
                        sessionId = "sleep-1",
                        deviceName = "Watch 2",
                    ),
                ),
            )

            val rows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(originalRowId, rows.single().rowId)
            assertEquals("SLEEP", rows.single().recordType)
            assertEquals("sleep-1", rows.single().sessionId)
            assertEquals("Watch 2", rows.single().deviceName)
        }

    @Test
    fun `identical hrv re-ingest keeps rowId stable and does not duplicate`() =
        runTest {
            hrvDao.upsertAll(listOf(HrvRecordEntity("id-1", 1000L, 40f, "SLEEP")))

            val firstRowId = hrvDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId
            hrvDao.upsertAll(listOf(HrvRecordEntity("id-1", 1000L, 40f, "SLEEP")))

            val rows = hrvDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(firstRowId, rows.single().rowId)
        }

    @Test
    fun `re-tagged hrv record updates session, record type, and device name in place`() =
        runTest {
            hrvDao.upsertAll(listOf(HrvRecordEntity("id-1", 1000L, 40f, "RESTING", deviceName = "Watch 1")))
            val originalRowId = hrvDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId

            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity(
                        "id-1",
                        1000L,
                        40f,
                        "SLEEP",
                        sessionId = "sleep-1",
                        deviceName = "Watch 2",
                    ),
                ),
            )

            val rows = hrvDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(originalRowId, rows.single().rowId)
            assertEquals("SLEEP", rows.single().recordType)
            assertEquals("sleep-1", rows.single().sessionId)
            assertEquals("Watch 2", rows.single().deviceName)
        }

    @Test
    fun `rowId zero ingestion auto-assigns a real rowid`() =
        runTest {
            heartRateDao.upsertAll(listOf(HeartRateRecordEntity("id-1", 1000L, 60, "SLEEP")))

            val row = heartRateDao.getByTimeRange(0, Long.MAX_VALUE).single()
            assertNotEquals(0L, row.rowId)
            assertNotNull(row.rowId)
        }

    @Test
    fun `re-upsert after deletion-by-source-record reinserts fresh`() =
        runTest {
            heartRateDao.upsertAll(listOf(HeartRateRecordEntity("id-1", 1000L, 60, "SLEEP")))
            assertEquals(1, heartRateDao.deleteBySourceRecordId("id-1"))

            heartRateDao.upsertAll(listOf(HeartRateRecordEntity("id-1", 1000L, 60, "SLEEP")))

            val rows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals("id-1", rows.single().id)
            assertEquals(60, rows.single().beatsPerMinute)
        }
}
