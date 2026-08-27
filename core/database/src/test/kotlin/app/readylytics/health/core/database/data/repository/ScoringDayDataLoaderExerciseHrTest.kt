package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * DB-001: verifies `ScoringDayDataLoader.loadExerciseHrSamples` delegates to the new SQL-filtered
 * `getByTypeAndTimeRange("EXERCISE", ...)` and no longer pulls the full time range via
 * `getByTimeRange` to filter in Kotlin.
 */
class ScoringDayDataLoaderExerciseHrTest {
    private val heartRateDao = mockk<HeartRateDao>()

    private val loader =
        ScoringDayDataLoader(
            workoutDao = mockk<WorkoutDao>(relaxed = true),
            sleepSessionDao = mockk<SleepSessionDao>(relaxed = true),
            dailySummaryDao = mockk<DailySummaryDao>(relaxed = true),
            heartRateDao = heartRateDao,
            minuteBucketDao = mockk(relaxed = true),
            weightRecordDao = mockk<WeightRecordDao>(relaxed = true),
            bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true),
            bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true),
            oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true),
            bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true),
        )

    @Test
    fun `loadExerciseHrSamples queries only EXERCISE records via SQL filter`() =
        runTest {
            val workouts =
                listOf(
                    WorkoutRecordEntity(
                        id = "w1",
                        startTime = 1_000L,
                        endTime = 2_000L,
                        exerciseType = "Run",
                        durationMinutes = 17,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 0f,
                        avgHr = 0f,
                    ),
                    WorkoutRecordEntity(
                        id = "w2",
                        startTime = 5_000L,
                        endTime = 9_000L,
                        exerciseType = "Bike",
                        durationMinutes = 67,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 0f,
                        avgHr = 0f,
                    ),
                )
            coEvery { heartRateDao.getByTypeAndTimeRange("EXERCISE", 1_000L, 9_000L) } returns emptyList()

            loader.loadExerciseHrSamples(workouts)

            coVerify(exactly = 1) { heartRateDao.getByTypeAndTimeRange("EXERCISE", 1_000L, 9_000L) }
            coVerify(exactly = 0) { heartRateDao.getByTimeRange(any(), any()) }
        }
}
