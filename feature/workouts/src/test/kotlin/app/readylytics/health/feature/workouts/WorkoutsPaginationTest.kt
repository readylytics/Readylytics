package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.ui.common.TimeRange
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsPaginationTest : WorkoutsViewModelTestFixture() {
    @Test
    fun `initial page is 1`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.currentPage.value)
            collectJob.cancel()
        }

    @Test
    fun `clamping behaves correctly when pages are updated`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }
            assertEquals(3, state.totalPages)
            assertEquals(1, state.currentPage)
            assertEquals(10, state.recentWorkouts.size)

            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(3, viewModel.currentPage.value)

            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(3, viewModel.currentPage.value)

            viewModel.onPreviousPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            collectJob.cancel()
        }

    @Test
    fun `page resets to 1 when range changes`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.currentPage.value)

            collectJob.cancel()
        }

    @Test
    fun `page resets to 1 when date changes`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            viewModel.onDateSelected(LocalDate.now().minusDays(1))
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.currentPage.value)

            collectJob.cancel()
        }

    @Test
    fun `page two requests offset ten and exposes only that page`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            val pageTwo = viewModel.uiState.first { it.currentPage == 2 }

            assertEquals(10, pageTwo.recentWorkouts.size)
            assertEquals(
                "11",
                pageTwo.recentWorkouts
                    .first()
                    .workout.id,
            )
            assertEquals(
                "20",
                pageTwo.recentWorkouts
                    .last()
                    .workout.id,
            )

            coVerify { workoutRepository.getInRangePaged(any(), any(), 10, 10) }

            collectJob.cancel()
        }

    @Test
    fun `final page holds the remainder`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            val pageThree = viewModel.uiState.first { it.currentPage == 3 }

            assertEquals(5, pageThree.recentWorkouts.size)
            assertEquals(
                "21",
                pageThree.recentWorkouts
                    .first()
                    .workout.id,
            )
            assertEquals(
                "25",
                pageThree.recentWorkouts
                    .last()
                    .workout.id,
            )

            collectJob.cancel()
        }

    @Test
    fun `shrinking repository count clamps the page`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 3 }
            assertEquals(3, viewModel.currentPage.value)

            workoutCount = 5
            viewModel.onPreviousPage()
            val clamped = viewModel.uiState.first { it.totalPages == 1 }
            assertEquals(1, clamped.currentPage)
            assertEquals(1, clamped.totalPages)

            collectJob.cancel()
        }

    @Test
    fun `previous press reaches page 1 with no dead press when count shrinks to two pages`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 3 }
            assertEquals(3, viewModel.currentPage.value)

            workoutCount = 15
            summariesFlow.value = listOf(DailySummary(date = LocalDate.now(), trimpWorkoutOnly = 0f))
            viewModel.uiState.first { it.totalPages == 2 }
            assertEquals(2, viewModel.uiState.value.currentPage)
            assertEquals(3, viewModel.currentPage.value)

            viewModel.onPreviousPage()
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.currentPage)

            collectJob.cancel()
        }
}
