package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.domain.model.DomainRouteLocation
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.model.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: WorkoutDetailViewModel

    private val workoutDetailLoader = mockk<WorkoutDetailLoader>()
    private val settingsRepository =
        mockk<UserPreferencesReader> {
            every { userPreferences } returns MutableStateFlow(UserPreferences())
        }
    private val syncWorkoutRouteUseCase = mockk<SyncWorkoutRouteUseCase>(relaxed = true)
    private val workoutDetailLayoutRepository =
        mockk<WorkoutDetailLayoutRepository>(relaxed = true) {
            every { layoutFor(any()) } returns flowOf(emptyList())
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel =
            WorkoutDetailViewModel(
                workoutDetailLoader = workoutDetailLoader,
                settingsRepo = settingsRepository,
                syncWorkoutRouteUseCase = syncWorkoutRouteUseCase,
                workoutDetailLayoutRepository = workoutDetailLayoutRepository,
                savedStateHandle = SavedStateHandle(),
            )
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    private fun buildWorkout(
        id: String,
        durationMinutes: Int = 30,
        exerciseType: String = "running",
        trimp: Float = 60f,
        avgHr: Float = 150f,
        startMs: Long = System.currentTimeMillis(),
    ): WorkoutData =
        WorkoutData(
            id = id,
            startTime = startMs,
            endTime = startMs + durationMinutes * 60 * 1000L,
            exerciseType = exerciseType,
            durationMinutes = durationMinutes,
            zone1Minutes = 5f,
            zone2Minutes = 10f,
            zone3Minutes = 10f,
            zone4Minutes = 5f,
            zone5Minutes = 0f,
            trimp = trimp,
            avgHr = avgHr,
        )

    private fun buildWorkoutDetailData(
        workout: WorkoutData,
        computedTrimp: Int = 116,
        gainedStrain: Float = 0.37f,
        gainedStrainDisplay: String = "0.37",
        classification: WorkoutLoadClassification? = null,
    ): WorkoutDetailData =
        WorkoutDetailData(
            workout = workout,
            hrSamples = emptyList(),
            hrChartData = emptyList(),
            durationMinutes = workout.durationMinutes,
            computedTrimp = computedTrimp,
            gainedStrain = gainedStrain,
            gainedStrainDisplay = gainedStrainDisplay,
            classification = classification,
            routeUiState = RouteUiState(),
            paceSpeedChartData = emptyList(),
            elevationChartData = emptyList(),
            isPaceMode = true,
            unitSystem = UnitSystem.METRIC,
        )

    @Test
    fun `detail state loads data from workoutDetailLoader and maps to uiState`() =
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
                buildWorkout(
                    id = "run-1",
                    durationMinutes = 62,
                    trimp = 115.6f,
                    avgHr = 134f,
                    startMs = startMs,
                )
            val classification =
                WorkoutLoadClassification(
                    totalTrimp = 115.6,
                    trimpPerMinute = 1.93,
                    baseLoad = WorkoutLoadLevel.MODERATE,
                    intensity = WorkoutIntensityLevel.HARD,
                    finalLoad = WorkoutLoadLevel.HARD,
                    wasPromoted = true,
                )
            val detailData =
                buildWorkoutDetailData(
                    workout = workout,
                    computedTrimp = 116,
                    gainedStrain = 0.37f,
                    gainedStrainDisplay = "0.37",
                    classification = classification,
                )

            coEvery { workoutDetailLoader.load("run-1", any()) } returns detailData

            viewModel.loadWorkout("run-1")
            advanceUntilIdle()

            assertEquals(116, viewModel.uiState.value.computedTrimp)
            assertEquals(0.37f, viewModel.uiState.value.gainedStrain)
            assertEquals("0.37", viewModel.uiState.value.gainedStrainDisplay)
            assertEquals(
                WorkoutLoadLevel.HARD,
                viewModel.uiState.value.classification
                    ?.finalLoad,
            )
            assertEquals(WorkoutLayoutType.RUNNING, viewModel.uiState.value.layoutType)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `unknown workout ID produces controlled error state and never throws`() =
        runTest {
            coEvery { workoutDetailLoader.load("unknown-id", any()) } returns null

            viewModel.loadWorkout("unknown-id")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.workout)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `valid ID in SavedStateHandle triggers load and survives recreation`() =
        runTest {
            val workout = buildWorkout(id = "run-1", durationMinutes = 62)
            val detailData = buildWorkoutDetailData(workout = workout, computedTrimp = 116)
            coEvery { workoutDetailLoader.load("run-1", any()) } returns detailData

            val restoredHandle = SavedStateHandle(mapOf("workoutId" to "run-1"))
            val recreatedViewModel =
                WorkoutDetailViewModel(
                    workoutDetailLoader = workoutDetailLoader,
                    settingsRepo = settingsRepository,
                    syncWorkoutRouteUseCase = syncWorkoutRouteUseCase,
                    workoutDetailLayoutRepository = workoutDetailLayoutRepository,
                    savedStateHandle = restoredHandle,
                )

            advanceUntilIdle()

            assertEquals(workout, recreatedViewModel.uiState.value.workout)
            assertEquals(116, recreatedViewModel.uiState.value.computedTrimp)
            assertFalse(recreatedViewModel.uiState.value.isLoading)
        }

    @Test
    fun `onRoutePermissionResult triggers syncWorkoutRouteUseCase and reloads workout`() =
        runTest {
            val workout = buildWorkout(id = "run-perm-test")
            val detailData =
                buildWorkoutDetailData(workout = workout).copy(
                    routeUiState = RouteUiState(state = RouteDataState.PermissionRequired),
                )
            coEvery { workoutDetailLoader.load("run-perm-test", any()) } returns detailData

            viewModel.loadWorkout("run-perm-test")
            advanceUntilIdle()

            assertEquals(RouteDataState.PermissionRequired, viewModel.uiState.value.routeUiState.state)

            viewModel.onRoutePermissionResult()
            advanceUntilIdle()

            coVerify(atLeast = 1) { syncWorkoutRouteUseCase.invoke("run-perm-test") }
            coVerify(atLeast = 2) { workoutDetailLoader.load("run-perm-test", any()) }
        }

    @Test
    fun `onRoutePermissionResult forwards the route granted by the per-session consent dialog`() =
        runTest {
            val workout = buildWorkout(id = "run-granted-route")
            val detailData = buildWorkoutDetailData(workout = workout)
            coEvery { workoutDetailLoader.load("run-granted-route", any()) } returns detailData

            val granted =
                listOf(
                    DomainRouteLocation(
                        time = Instant.ofEpochMilli(workout.startTime),
                        latitude = 37.7749,
                        longitude = -122.4194,
                        altitudeMeters = 10.0,
                        horizontalAccuracyMeters = 5f,
                        verticalAccuracyMeters = 3f,
                    ),
                )

            viewModel.loadWorkout("run-granted-route")
            advanceUntilIdle()

            viewModel.onRoutePermissionResult(granted)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                syncWorkoutRouteUseCase.invoke("run-granted-route", granted)
            }
        }
}
