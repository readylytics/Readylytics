package app.readylytics.health.ui.workouts

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.WorkoutDisplayMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: WorkoutDetailViewModel

    private val workoutRepository = mockk<WorkoutRepository>()
    private val healthConnectRepository = mockk<HealthConnectRepository>(relaxed = true)
    private val heartRateRepository = mockk<HeartRateRepository>(relaxed = true)
    private val dailySummaryRepository = mockk<DailySummaryRepository>(relaxed = true)
    private val settingsRepository =
        mockk<SettingsRepository> {
            every { userPreferences } returns MutableStateFlow(UserPreferences())
        }
    private val getWorkoutDisplayMetricsUseCase = mockk<GetWorkoutDisplayMetricsUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel =
            WorkoutDetailViewModel(
                workoutRepository = workoutRepository,
                hcRepo = healthConnectRepository,
                heartRateRepository = heartRateRepository,
                dailySummaryRepository = dailySummaryRepository,
                settingsRepo = settingsRepository,
                getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
            )
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `detail state uses rounded load metrics from shared use case`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(19)
                    .plusMinutes(28)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 62 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 62,
                    zone1Minutes = 0f,
                    zone2Minutes = 10f,
                    zone3Minutes = 20f,
                    zone4Minutes = 32f,
                    zone5Minutes = 0f,
                    trimp = 115.6f,
                    avgHr = 134f,
                )
            val dbSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr-1",
                        timestampMs = workout.startTime + 1_000L,
                        beatsPerMinute = 134,
                        recordType = "EXERCISE",
                    ),
                )
            coEvery { workoutRepository.getById("run-1") } returns workout
            coEvery { healthConnectRepository.readHeartRateSamples(any(), any()) } returns emptyList()
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns dbSamples
            coEvery { dailySummaryRepository.getByDate(any()) } returns
                DailySummary(date = date, totalTrimp = 115.6f, rhrBpm = 52f, totalPai = 12f)
            coEvery { dailySummaryRepository.getSince(any()) } returns
                listOf(DailySummary(date = date, totalTrimp = 115.6f, rhrBpm = 52f, paiScore = 12f))
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = any(),
                )
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 115.6f,
                    computedTrimp = 116,
                    trimpDisplay = "116",
                    gainedStrain = 0.37f,
                    gainedStrainDisplay = "0.37",
                )

            viewModel.loadWorkout("run-1")
            advanceUntilIdle()

            assertEquals(116, viewModel.uiState.value.computedTrimp)
            assertEquals(0.37f, viewModel.uiState.value.gainedStrain)
            assertEquals("0.37", viewModel.uiState.value.gainedStrainDisplay)
        }
}
