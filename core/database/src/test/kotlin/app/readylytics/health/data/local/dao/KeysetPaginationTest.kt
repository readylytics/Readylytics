package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.HealthSourceRecordEntity
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
    fun testHeartRateKeysetPagination() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            val records =
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = 1000L,
                        beatsPerMinute = 65,
                        recordType = "RESTING",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 3L,
                        timestampMs = 2000L,
                        beatsPerMinute = 70,
                        recordType = "RESTING",
                    ),
                )
            heartRateDao.upsertAll(records)

            val page1 =
                heartRateDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = 0L,
                    lastSourceRecordRef = 0L,
                    limit = 2,
                )
            assertEquals(2, page1.size)
            assertEquals(1L, page1[0].sourceRecordRef)
            assertEquals(2L, page1[1].sourceRecordRef)

            val page2 =
                heartRateDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = page1.last().timestampMs,
                    lastSourceRecordRef = page1.last().sourceRecordRef,
                    limit = 2,
                )
            assertEquals(1, page2.size)
            assertEquals(3L, page2[0].sourceRecordRef)
        }

    @Test
    fun testHrvKeysetPagination() =
        runTest {
            seedSourceRecordParents(1L, 2L, 3L)
            val records =
                listOf(
                    HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 1000L, rmssdMs = 45f, recordType = "RESTING"),
                    HrvRecordEntity(sourceRecordRef = 2L, timestampMs = 1000L, rmssdMs = 50f, recordType = "RESTING"),
                    HrvRecordEntity(sourceRecordRef = 3L, timestampMs = 2000L, rmssdMs = 55f, recordType = "RESTING"),
                )
            hrvDao.upsertAll(records)

            val page1 =
                hrvDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = 0L,
                    lastSourceRecordRef = 0L,
                    limit = 2,
                )
            assertEquals(2, page1.size)
            assertEquals(1L, page1[0].sourceRecordRef)
            assertEquals(2L, page1[1].sourceRecordRef)

            val page2 =
                hrvDao.getKeysetPage(
                    startMs = 500L,
                    endMs = 2500L,
                    lastTimestampMs = page1.last().timestampMs,
                    lastSourceRecordRef = page1.last().sourceRecordRef,
                    limit = 2,
                )
            assertEquals(1, page2.size)
            assertEquals(3L, page2[0].sourceRecordRef)
        }
}
