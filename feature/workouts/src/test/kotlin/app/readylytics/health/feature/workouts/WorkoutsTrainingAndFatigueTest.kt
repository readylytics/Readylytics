package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsTrainingAndFatigueTest : WorkoutsViewModelTestFixture() {
    @Test
    fun `weekly training compares week-to-date against the full previous week`() =
        runTest(testDispatcher) {
            selectedDateFlow.value = LocalDate.of(2026, 6, 4)
            workouts.addAll(
                listOf(
                    workoutOnDate(LocalDate.of(2026, 6, 2), durationMinutes = 30),
                    workoutOnDate(LocalDate.of(2026, 5, 26), durationMinutes = 60),
                    workoutOnDate(LocalDate.of(2026, 5, 29), durationMinutes = 15),
                    workoutOnDate(LocalDate.of(2026, 5, 22), durationMinutes = 999),
                ),
            )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val stats = viewModel.uiState.value.weeklyTraining!!
            assertEquals(30, stats.currentWeek.totalDurationMinutes)
            assertEquals(75, stats.previousWeek.totalDurationMinutes)
            assertEquals(-45, stats.comparison.durationDeltaMinutes)
            assertEquals(1, stats.currentWeek.workoutCount)
            assertEquals(1, stats.currentWeek.activeDays)
            collectJob.cancel()
        }

    @Test
    fun `weekly training is null before any load completes`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()

            assertNull(viewModel.uiState.value.weeklyTraining)
        }

    @Test
    fun `weekly training updates when workout data refreshes`() =
        runTest(testDispatcher) {
            selectedDateFlow.value = LocalDate.of(2026, 6, 4)
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(
                0,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.workoutCount,
            )

            workouts.addAll(listOf(workoutOnDate(LocalDate.of(2026, 6, 2), durationMinutes = 30)))
            summariesFlow.value = listOf(mockk<DailySummary>(relaxed = true))
            testScheduler.advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.workoutCount,
            )
            collectJob.cancel()
        }

    @Test
    fun `changing the week start day preference recomputes weekly training`() =
        runTest(testDispatcher) {
            selectedDateFlow.value = LocalDate.of(2026, 6, 4)
            workouts.addAll(listOf(workoutOnDate(LocalDate.of(2026, 5, 31), durationMinutes = 60)))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(
                0,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.totalDurationMinutes,
            )

            preferencesFlow.value = preferencesFlow.value.copy(weekStartDay = DayOfWeek.SUNDAY)
            testScheduler.advanceUntilIdle()
            assertEquals(
                60,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.totalDurationMinutes,
            )
            collectJob.cancel()
        }

    @Test
    fun `workoutsViewModel loads 24h residual fatigue curve for selected day`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 8, 29)
            selectedDateFlow.value = selectedDate
            val startOfDayMs = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val workout =
                WorkoutData(
                    id = "fatigue-run-1",
                    startTime = startOfDayMs + 8 * 3600 * 1000L,
                    endTime = startOfDayMs + 9 * 3600 * 1000L + 7 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 67,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 100f,
                    avgHr = 150f,
                )
            workouts.add(workout)
            coEvery { workoutRepository.getCanonicalFatigueSeed(any()) } returns
                listOf(
                    FatigueWorkoutInput(
                        workoutId = "fatigue-run-1",
                        endTimeMs = startOfDayMs + 9 * 3600 * 1000L + 7 * 60 * 1000L,
                        trimp = 100f,
                    ),
                )
            preferencesFlow.value =
                UserPreferences(
                    residualFatigueHalfLifeHours = 24f,
                    residualFatigueGain = 1.0f,
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { it.residualFatigueCurve.isNotEmpty() }
            assertEquals(97, state.residualFatigueCurve.size)
            collectJob.cancelAndJoin()
        }

    @Test
    fun `workoutsViewModel updates fatigue curve when fatigue range changes to 3D or 7D`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 8, 29)
            selectedDateFlow.value = selectedDate
            preferencesFlow.value =
                UserPreferences(
                    residualFatigueHalfLifeHours = 24f,
                    residualFatigueGain = 1.0f,
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val state1D = viewModel.uiState.first { it.residualFatigueCurve.isNotEmpty() }
            assertEquals(FatigueCurveRange.ONE_DAY, state1D.selectedFatigueRange)
            assertEquals(96, state1D.residualFatigueCurve.size)

            viewModel.onFatigueRangeSelected(FatigueCurveRange.THREE_DAYS)
            testScheduler.advanceUntilIdle()

            val state3D = viewModel.uiState.first { it.selectedFatigueRange == FatigueCurveRange.THREE_DAYS }
            assertEquals(FatigueCurveRange.THREE_DAYS, state3D.selectedFatigueRange)
            assertEquals(3 * 96, state3D.residualFatigueCurve.size)

            viewModel.onFatigueRangeSelected(FatigueCurveRange.SEVEN_DAYS)
            testScheduler.advanceUntilIdle()

            val state7D = viewModel.uiState.first { it.selectedFatigueRange == FatigueCurveRange.SEVEN_DAYS }
            assertEquals(FatigueCurveRange.SEVEN_DAYS, state7D.selectedFatigueRange)
            assertEquals(7 * 96, state7D.residualFatigueCurve.size)

            collectJob.cancelAndJoin()
        }
}
