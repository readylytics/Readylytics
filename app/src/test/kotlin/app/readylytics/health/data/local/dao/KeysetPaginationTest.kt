package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class KeysetPaginationTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun testHeartRateKeysetPagination() =
        runTest {
            val records =
                listOf(
                    HeartRateRecordEntity(id = "hr1", timestampMs = 1000L, beatsPerMinute = 60, recordType = "RESTING"),
                    HeartRateRecordEntity(id = "hr2", timestampMs = 1000L, beatsPerMinute = 65, recordType = "RESTING"),
                    HeartRateRecordEntity(id = "hr3", timestampMs = 2000L, beatsPerMinute = 70, recordType = "RESTING"),
                )
            heartRateDao.upsertAll(records)

            val page1 =
                heartRateDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = 0L,
                    lastId = "",
                    limit = 2,
                )
            assertEquals(2, page1.size)
            assertEquals("hr1", page1[0].id)
            assertEquals("hr2", page1[1].id)

            val page2 =
                heartRateDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = page1.last().timestampMs,
                    lastId = page1.last().id,
                    limit = 2,
                )
            assertEquals(1, page2.size)
            assertEquals("hr3", page2[0].id)
        }

    @Test
    fun testHrvKeysetPagination() =
        runTest {
            val records =
                listOf(
                    HrvRecordEntity(id = "hrv1", timestampMs = 1000L, rmssdMs = 45f, recordType = "RESTING"),
                    HrvRecordEntity(id = "hrv2", timestampMs = 1000L, rmssdMs = 50f, recordType = "RESTING"),
                    HrvRecordEntity(id = "hrv3", timestampMs = 2000L, rmssdMs = 55f, recordType = "RESTING"),
                )
            hrvDao.upsertAll(records)

            val page1 =
                hrvDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = 0L,
                    lastId = "",
                    limit = 2,
                )
            assertEquals(2, page1.size)
            assertEquals("hrv1", page1[0].id)
            assertEquals("hrv2", page1[1].id)

            val page2 =
                hrvDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = page1.last().timestampMs,
                    lastId = page1.last().id,
                    limit = 2,
                )
            assertEquals(1, page2.size)
            assertEquals("hrv3", page2[0].id)
        }
}
