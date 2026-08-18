package app.readylytics.health.data.local.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.DailySummaryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailySummaryDaoQueryTest {
    private lateinit var database: HealthDatabase
    private lateinit var dailySummaryDao: DailySummaryDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dailySummaryDao = database.dailySummaryDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `hasAnyWorkoutOnlyTrimpData returns false when no rows have trimpWorkoutOnly`() =
        runTest {
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = 0L, trimpWorkoutOnly = null))

            assertFalse(dailySummaryDao.hasAnyWorkoutOnlyTrimpData())
        }

    @Test
    fun `hasAnyWorkoutOnlyTrimpData returns true when at least one row has trimpWorkoutOnly`() =
        runTest {
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = 0L, trimpWorkoutOnly = null))
            dailySummaryDao.upsert(DailySummaryEntity(dateMidnightMs = 86400000L, trimpWorkoutOnly = 42f))

            assertTrue(dailySummaryDao.hasAnyWorkoutOnlyTrimpData())
        }

    @Test
    fun `hasAnyWorkoutOnlyTrimpData returns false on empty database`() =
        runTest {
            assertFalse(dailySummaryDao.hasAnyWorkoutOnlyTrimpData())
        }
}
