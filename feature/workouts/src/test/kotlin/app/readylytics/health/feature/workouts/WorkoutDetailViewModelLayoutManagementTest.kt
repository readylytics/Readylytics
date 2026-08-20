package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelLayoutManagementTest {
    private val dispatcher = StandardTestDispatcher()
    private val layoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true)
    private val storedLayout = MutableStateFlow(SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS)

    private fun viewModel(stored: Flow<List<WorkoutDetailItemConfiguration>> = storedLayout): WorkoutDetailViewModel {
        every { layoutRepository.layoutFor(any()) } returns stored
        return WorkoutDetailViewModel(
            workoutRepository = mockk(relaxed = true),
            hcRepo = mockk(relaxed = true),
            heartRateRepository = mockk(relaxed = true),
            dailySummaryRepository = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            getWorkoutDisplayMetricsUseCase = mockk(relaxed = true),
            syncWorkoutRouteUseCase = mockk(relaxed = true),
            workoutDetailLayoutRepository = layoutRepository,
            savedStateHandle = SavedStateHandle(),
            defaultDispatcher = dispatcher,
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `layout configuration is collected from the repository`() =
        runTest(dispatcher) {
            val storedWithHiddenRas =
                SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.map {
                    if (it.itemId == WorkoutDetailItemId.RAS) it.copy(isVisible = false) else it
                }
            val vm = viewModel(MutableStateFlow(storedWithHiddenRas))
            advanceUntilIdle()

            assertEquals(storedWithHiddenRas, vm.uiState.value.itemConfigurations)
            assertFalse(vm.uiState.value.isManagingLayout)
        }

    @Test
    fun `item configurations default to the shared defaults when nothing is stored`() =
        runTest(dispatcher) {
            val vm = viewModel(emptyFlow())
            advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS, vm.uiState.value.itemConfigurations)
            assertFalse(vm.uiState.value.isManagingLayout)
        }

    @Test
    fun `toggling management enters edit mode and toggling again persists`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onToggleLayoutManagement()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isManagingLayout)

            vm.onToggleItemVisibility(WorkoutDetailItemId.RAS, false)
            advanceUntilIdle()
            assertFalse(
                vm.uiState.value.itemConfigurations
                    .first { it.itemId == WorkoutDetailItemId.RAS }
                    .isVisible,
            )

            vm.onToggleLayoutManagement()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isManagingLayout)
            coVerify { layoutRepository.updateLayout(any(), any()) }
        }

    @Test
    fun `cancelling discards pending edits without persisting`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onToggleLayoutManagement()
            vm.onToggleItemVisibility(WorkoutDetailItemId.RAS, false)
            advanceUntilIdle()

            vm.onCancelLayoutManagement()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isManagingLayout)
            assertTrue(
                vm.uiState.value.itemConfigurations
                    .first { it.itemId == WorkoutDetailItemId.RAS }
                    .isVisible,
            )
            coVerify(exactly = 0) { layoutRepository.updateLayout(any(), any()) }
        }

    @Test
    fun `reordering renumbers positions and keeps hidden items`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onToggleLayoutManagement()
            advanceUntilIdle()

            val reversed: List<WorkoutDetailItemConfiguration> =
                SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.reversed()
            vm.onReorderItems(reversed)
            advanceUntilIdle()

            val result = vm.uiState.value.itemConfigurations
            assertEquals(WorkoutDetailItemId.RECOVERY_HRR, result.first().itemId)
            assertEquals(result.indices.toList(), result.map { it.position })
        }

    @Test
    fun `reset restores the shared defaults into the pending edit`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.onToggleLayoutManagement()
            vm.onToggleItemVisibility(WorkoutDetailItemId.RAS, false)
            advanceUntilIdle()

            vm.onResetLayoutToDefaults()
            advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS, vm.uiState.value.itemConfigurations)
        }
}
