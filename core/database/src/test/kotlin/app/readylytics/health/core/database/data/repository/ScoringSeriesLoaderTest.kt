package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScoringSeriesLoaderTest {
    private val zone = ZoneId.of("UTC")
    private val workoutDao = mockk<WorkoutDao>()
    private val dailySummaryDao = mockk<DailySummaryDao>()
    private val loader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
    private val target = LocalDate.of(2026, 6, 1)

    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `returns null when no data exists`() =
        runTest {
            coEvery { dailySummaryDao.getEarliestDateMs() } returns null
            coEvery { workoutDao.getEarliestWorkoutTimestamp() } returns null

            assertNull(loader.loadTrackingStartDate(zone))
        }

    @Test
    fun `returns the earlier of first summary and first workout`() =
        runTest {
            coEvery { dailySummaryDao.getEarliestDateMs() } returns ms(target.minusDays(10))
            coEvery { workoutDao.getEarliestWorkoutTimestamp() } returns ms(target.minusDays(40)) + 3_600_000L

            assertEquals(target.minusDays(40), loader.loadTrackingStartDate(zone))
        }

    @Test
    fun `skips the workout lookup when the summary date already satisfies the guard`() =
        runTest {
            coEvery { dailySummaryDao.getEarliestDateMs() } returns ms(target.minusDays(100))

            val result = loader.loadTrackingStartDate(zone, satisfiedBefore = target.minusDays(27))

            assertEquals(target.minusDays(100), result)
            coVerify(exactly = 0) { workoutDao.getEarliestWorkoutTimestamp() }
        }

    @Test
    fun `still checks workouts when the summary date does not satisfy the guard`() =
        runTest {
            coEvery { dailySummaryDao.getEarliestDateMs() } returns ms(target.minusDays(5))
            coEvery { workoutDao.getEarliestWorkoutTimestamp() } returns ms(target.minusDays(90))

            val result = loader.loadTrackingStartDate(zone, satisfiedBefore = target.minusDays(27))

            assertEquals(target.minusDays(90), result)
        }
}
