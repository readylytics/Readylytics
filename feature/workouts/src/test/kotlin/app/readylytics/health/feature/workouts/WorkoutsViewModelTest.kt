package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.repository.WorkoutData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsViewModelTest : WorkoutsViewModelTestFixture() {
    @Test
    fun `new workout history can cross seven-day tenure without resubscribing`() =
        runTest(testDispatcher) {
            val selectedDate = selectedDateFlow.value
            val zoneId = ZoneId.systemDefault()
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returnsMany
                listOf(
                    selectedDate
                        .minusDays(5)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                    selectedDate
                        .minusDays(6)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.uiState.value.todayStrainIncrease)

            summariesFlow.value = listOf(DailySummary(date = selectedDate, trimpWorkoutOnly = 0f))
            testScheduler.advanceUntilIdle()

            assertEquals(0f, viewModel.uiState.value.todayStrainIncrease!!, 0.001f)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `isSyncing toggle does not restart the heavy pipeline`() =
        runTest(testDispatcher) {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = System.currentTimeMillis() - 1000 * 60 * 30,
                    endTime = System.currentTimeMillis(),
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )
            workouts.add(workout)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.isLoading)
            assertEquals(true, viewModel.uiState.value.isRefreshing)

            isSyncingFlow.value = false
            testScheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)

            coVerify(exactly = 1) { workoutRepository.getInRangePaged(any(), any(), any(), any()) }
            coVerify(exactly = 1) { workoutRepository.countByTimeRange(any(), any()) }
            coVerify(exactly = 1) { workoutRepository.getEarliestWorkoutTimestamp() }
            assertSame(stateBeforeToggle.recentWorkouts, stateAfterToggle.recentWorkouts)

            collectJob.cancel()
        }

    @Test
    fun unrelatedPreferenceChange_doesNotRestartDatabaseSubscriptions() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            preferencesFlow.value =
                preferencesFlow.value.copy(
                    dynamicColorEnabled = !preferencesFlow.value.dynamicColorEnabled,
                )
            testScheduler.advanceUntilIdle()

            verify(exactly = 1) { dailySummaryRepository.observeLatest() }
            verify(exactly = 2) { dailySummaryRepository.observeSince(any()) }
            collectJob.cancelAndJoin()
        }

    @Test
    fun `isLoading stays true while syncing when no workouts or summary exist yet`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(true, state.isLoading)
            assertEquals(true, state.isRefreshing)

            collectJob.cancel()
        }

    @Test
    fun `heart-rate samples are batched, not fetched once per workout`() =
        runTest(testDispatcher) {
            val dummyWorkouts =
                (1..5).map { id ->
                    WorkoutData(
                        id = id.toString(),
                        startTime = System.currentTimeMillis() - (id * 1000 * 60),
                        endTime = System.currentTimeMillis() - (id * 1000 * 60) + 1000 * 30,
                        exerciseType = "running",
                        durationMinutes = 30,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    )
                }
            workouts.addAll(dummyWorkouts)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            viewModel.uiState.first { it.recentWorkouts.size == 5 }

            val startMs = dummyWorkouts.minOf { it.startTime }
            val endMs = dummyWorkouts.maxOf { it.endTime }
            coVerify(exactly = 2) {
                heartRateRepository.countInRangeOfType(RecordType.EXERCISE.name, startMs, endMs)
            }
            coVerify(exactly = 2) {
                heartRateRepository.getByTimeRangeOfType(RecordType.EXERCISE.name, startMs, endMs)
            }
            coVerify(exactly = 0) { heartRateRepository.getByTimeRange(any(), any()) }
            coVerify(exactly = 0) {
                heartRateRepository.forEachByTimeRangeOfTypePage(any(), any(), any(), any(), any())
            }

            collectJob.cancel()
        }
}
