package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsMetricsTest : WorkoutsViewModelTestFixture() {
    @Test
    fun `display metrics are computed only for visible page rows`() =
        runTest(testDispatcher) {
            val yesterday =
                LocalDate
                    .now()
                    .minusDays(1)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            workouts.addAll(workoutPageFixtures(25, startTimeMs = yesterday + 23 * 60 * 60 * 1000L))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            assertEquals(10, state.recentWorkouts.size)
            assertEquals(3, state.totalPages)

            coVerify(exactly = 10) {
                getWorkoutDisplayMetricsUseCase.execute(any(), any(), any(), any())
            }

            collectJob.cancel()
        }

    @Test
    fun `recent workout uses rounded load metrics from shared use case`() =
        runTest(testDispatcher) {
            arrangeRoundedLoadMetrics()

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            assertEquals(0.37f, state.recentWorkouts.single().gainedStrain)
            assertEquals("0.37", state.recentWorkouts.single().gainedStrainDisplay)
            assertEquals(116, state.recentWorkouts.single().computedTrimp)
            assertEquals(
                WorkoutLoadLevel.HARD,
                state.recentWorkouts
                    .single()
                    .classification
                    ?.finalLoad,
            )

            collectJob.cancelAndJoin()
        }

    @Test
    fun `stats state exposes canonical latest daily metrics`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            summariesFlow.value =
                listOf(
                    DailySummary(
                        date = today,
                        readinessWorkoutOnly = 72.5f,
                        strainRatioWorkoutOnly = 0.365f,
                    ),
                )
            every { dailySummaryRepository.observeLatest() } returns flowOf(summariesFlow.value.single())

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.latestMetrics != null }

            assertEquals(73, state.latestMetrics?.readinessRounded)
            assertEquals("0.37", state.latestMetrics?.strainRatioDisplay)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `stats state sums todayStrainIncrease from workout-only gains`() =
        runTest(testDispatcher) {
            arrangeWorkoutOnlyGains()

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.todayStrainIncrease != null }

            assertEquals(0.18f, state.todayStrainIncrease!!, 0.001f)
            collectJob.cancelAndJoin()
        }

    @Test
    fun `stats state computes todayStrainIncrease from whole-day ATL-CTL diff in everyday-HR mode`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            summariesFlow.value =
                listOf(
                    DailySummary(date = today.minusDays(8), trimpEverydayHr = 5f),
                    DailySummary(
                        date = today,
                        readinessWorkoutOnly = 72.5f,
                        strainRatioWorkoutOnly = 0.365f,
                        trimpEverydayHr = 15f,
                    ),
                )
            every { dailySummaryRepository.observeLatest() } returns
                flowOf(summariesFlow.value.first { it.date == today })
            preferencesFlow.value =
                UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)

            every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(today to 10f)
            every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(today to 15f)
            every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
            every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f
            every { scoringCalculator.computeAtlEmaWithDecay(match { it[today] == 0f }, today) } returns 12f
            every { scoringCalculator.computeCtlEmaWithDecay(match { it[today] == 0f }, today) } returns 10f

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.todayStrainIncrease != null }

            assertEquals(0.3f, state.todayStrainIncrease!!, 0.001f)
            collectJob.cancelAndJoin()
        }

    @Test
    fun `todayStrainIncrease is non-null in everyday-HR mode with thirty days of summaries and zero workouts`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            summariesFlow.value =
                (0..29).map { daysAgo ->
                    DailySummary(date = today.minusDays(daysAgo.toLong()), trimpEverydayHr = 20f)
                }
            preferencesFlow.value =
                UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)

            every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(today to 10f)
            every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(today to 15f)
            every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
            every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f
            every { scoringCalculator.computeAtlEmaWithDecay(match { it[today] == 0f }, today) } returns 12f
            every { scoringCalculator.computeCtlEmaWithDecay(match { it[today] == 0f }, today) } returns 10f

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.todayStrainIncrease != null }

            assertEquals(0.3f, state.todayStrainIncrease!!, 0.001f)
            collectJob.cancelAndJoin()
        }

    @Test
    fun `scoring zone determines selected-day workout membership`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 6, 9)
            val originalDeviceZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            try {
                selectedDateFlow.value = selectedDate
                preferencesFlow.value = UserPreferences(scoringZoneId = "Pacific/Honolulu")
                val startTime = Instant.parse("2026-06-10T05:00:00Z").toEpochMilli()
                val workout =
                    createTimedWorkout("zone-edge", startTime, durationMinutes = 30, trimp = 30f, avgHr = 120f)
                workouts.add(workout)

                viewModel = createViewModel()
                val collectJob = launch { viewModel.uiState.collect {} }
                testScheduler.advanceUntilIdle()

                assertEquals(
                    listOf("zone-edge"),
                    viewModel.uiState.value.recentWorkouts
                        .map { it.workout.id },
                )

                collectJob.cancelAndJoin()
            } finally {
                TimeZone.setDefault(originalDeviceZone)
            }
        }
}
