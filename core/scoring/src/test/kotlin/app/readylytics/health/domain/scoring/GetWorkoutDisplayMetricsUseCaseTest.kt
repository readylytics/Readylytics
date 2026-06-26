package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GetWorkoutDisplayMetricsUseCaseTest {
    private val dailySummaryRepository = mockk<DailySummaryRepository>()
    private val heartRateRepository = mockk<HeartRateRepository>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val computeWorkoutLoadMetricsUseCase = mockk<ComputeWorkoutLoadMetricsUseCase>()

    private val useCase =
        GetWorkoutDisplayMetricsUseCase(
            dailySummaryRepository = dailySummaryRepository,
            heartRateRepository = heartRateRepository,
            settingsRepo = settingsRepo,
            computeWorkoutLoadMetricsUseCase = computeWorkoutLoadMetricsUseCase,
        )

    @Test
    fun `executes calculations and fetches history correctly`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val workoutDate = LocalDate.of(2026, 6, 9)
            val startMs =
                workoutDate
                    .atStartOfDay(zoneId)
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()

            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )

            val prefs = UserPreferences()
            every { settingsRepo.userPreferences } returns MutableStateFlow(prefs)

            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = mockk<DailySummary>()
            every { summary.rhrBpm } returns 55f
            coEvery { dailySummaryRepository.getByDate(midnight) } returns summary

            val fortyTwoDaysAgo =
                workoutDate
                    .minusDays(ScoringConstants.CHRONIC_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            coEvery { dailySummaryRepository.getSince(fortyTwoDaysAgo) } returns emptyList()

            val dbSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr-1",
                        timestampMs = startMs + 1000L,
                        beatsPerMinute = 130,
                        recordType = "EXERCISE",
                    ),
                )
            coEvery { heartRateRepository.getByTimeRange(workout.startTime, workout.endTime) } returns dbSamples

            val loadMetrics =
                ComputeWorkoutLoadMetricsUseCase.WorkoutLoadMetrics(
                    preciseTrimp = 50f,
                    roundedTrimp = 50,
                    preciseGainedStrain = 0.36f,
                    roundedGainedStrain = 0.36f,
                    gainedStrainDisplay = "0.36",
                )
            every {
                computeWorkoutLoadMetricsUseCase.execute(
                    workout = workout,
                    workoutDate = workoutDate,
                    samples = any(),
                    prefs = prefs,
                    restingHrBaseline = 55f,
                    trimpByDate = any(),
                )
            } returns loadMetrics

            val result = useCase.execute(workout = workout)

            assertEquals(50f, result.preciseTrimp)
            assertEquals(50, result.computedTrimp)
            assertEquals("50", result.trimpDisplay)
            assertEquals(0.36f, result.gainedStrain)
            assertEquals("0.36", result.gainedStrainDisplay)

            coVerify {
                dailySummaryRepository.getByDate(midnight)
                dailySummaryRepository.getSince(fortyTwoDaysAgo)
                heartRateRepository.getByTimeRange(workout.startTime, workout.endTime)
            }
        }
}
