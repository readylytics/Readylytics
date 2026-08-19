package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OffsetPaginationTest {
    private lateinit var db: HealthDatabase
    private lateinit var dao: WorkoutDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.workoutDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getPagedInRange returns newest-first within half-open window`() =
        runTest {
            val records = (1..5).map { workoutEntity("w$it", startTime = it * 1000L) }
            dao.upsertAll(records)

            // [2000, 5000): records 2, 3, 4 -> DESC by startTime: w4, w3, w2
            val paged = dao.getPagedInRange(2000L, 5000L, 2, 0)
            assertEquals(2, paged.size)
            assertEquals("w4", paged[0].id)
            assertEquals("w3", paged[1].id)

            val page2 = dao.getPagedInRange(2000L, 5000L, 2, 2)
            assertEquals(1, page2.size)
            assertEquals("w2", page2[0].id)

            // toMs is exclusive: the record exactly at 5000 is not counted
            assertEquals(3, dao.countByTimeRange(2000L, 5000L))
        }

    @Test
    fun `getPagedInRange breaks equal startTime ties by id descending`() =
        runTest {
            dao.upsertAll(
                listOf(
                    workoutEntity("wA", startTime = 1000L),
                    workoutEntity("wB", startTime = 1000L),
                    workoutEntity("wC", startTime = 1000L),
                ),
            )

            val paged = dao.getPagedInRange(0L, 2000L, 10, 0)
            assertEquals(listOf("wC", "wB", "wA"), paged.map { it.id })
        }

    @Test
    fun `getPagedInRange orders descending`() =
        runTest {
            val records = (1..3).map { workoutEntity("w$it", startTime = it * 1000L) }
            dao.upsertAll(records)

            val pagedRange = dao.getPagedInRange(0L, 4000L, 10, 0)
            assertEquals(listOf("w3", "w2", "w1"), pagedRange.map { it.id })
        }

    @Test
    fun `getPagedInRange returns final partial page`() =
        runTest {
            val records = (1..25).map { workoutEntity("w$it", startTime = it * 1000L) }
            dao.upsertAll(records)

            val page3 = dao.getPagedInRange(0L, 26_000L, 10, 20)
            assertEquals(5, page3.size)
            assertEquals("w5", page3[0].id)
            assertEquals("w1", page3[4].id)

            assertEquals(25, dao.countByTimeRange(0L, 26_000L))
        }

    @Test
    fun `countByTimeRange counts only records within half-open window`() =
        runTest {
            dao.upsertAll(
                listOf(
                    workoutEntity("before", startTime = 999L),
                    workoutEntity("atFrom", startTime = 1000L),
                    workoutEntity("inside", startTime = 1500L),
                    workoutEntity("atTo", startTime = 2000L),
                    workoutEntity("after", startTime = 2001L),
                ),
            )

            // [1000, 2000): atFrom and inside, but not atTo (2000 is excluded)
            assertEquals(2, dao.countByTimeRange(1000L, 2000L))
        }

    private fun workoutEntity(
        id: String,
        startTime: Long,
    ) = WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = startTime + 60_000L,
        exerciseType = "Running",
        durationMinutes = 1,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 1f,
        avgHr = 120f,
    )
}
