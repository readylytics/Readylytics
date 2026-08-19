package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WeightRecordDaoTest {
    private lateinit var db: HealthDatabase
    private lateinit var dao: WeightRecordDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.weightRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getPagedByTimeRange returns subset ordered and countByTimeRange is correct`() =
        runTest {
            // Setup data
            val records =
                (1..5).map {
                    WeightRecordEntity(
                        id = "w$it",
                        timestampMs = it * 1000L,
                        weightKg = 80f - it,
                        deviceName = null,
                    )
                }
            dao.upsertAll(records)

            // We want fromMs = 2000L (inclusive), toMs = 5000L (exclusive)
            // This matches records 2, 3, 4. So timestamps: 2000, 3000, 4000.
            // Order is DESC by timestamp, then DESC by id.
            // So expected order: w4 (4000L), w3 (3000L), w2 (2000L)
            val paged = dao.getPagedByTimeRange(2000L, 5000L, 2, 0)
            assertEquals(2, paged.size)
            assertEquals("w4", paged[0].id)
            assertEquals("w3", paged[1].id)

            val pagedPage2 = dao.getPagedByTimeRange(2000L, 5000L, 2, 2)
            assertEquals(1, pagedPage2.size)
            assertEquals("w2", pagedPage2[0].id)

            // And exactly at toMs is excluded (5000L is excluded)
            val count = dao.countByTimeRange(2000L, 5000L)
            assertEquals(3, count)
        }
}
