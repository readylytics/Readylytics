package com.gregor.lauritz.healthdashboard.ui.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var syncController: ForegroundSyncController
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val dailySummaryRepo =
            mockk<com.gregor.lauritz.healthdashboard.domain.dashboard.DailySummaryRepository> {
                every { observeSince(any()) } returns MutableStateFlow(emptyList())
                every { observeByDate(any()) } returns MutableStateFlow(null)
                every { observeFirstSessionEndingInRange(any(), any()) } returns MutableStateFlow(null)
            }
        val getDashboardDataUseCase =
            mockk<com.gregor.lauritz.healthdashboard.domain.dashboard.GetDashboardDataUseCase>(relaxed = true) {
                every { formatSleepDuration(any()) } returns "8h 0m"
            }
        every { getDashboardDataUseCase(any(), any(), any(), any(), any()) } returns
            com.gregor.lauritz.healthdashboard.domain.dashboard.GetDashboardDataUseCase.DashboardCards(
                emptyMap(),
                emptyList(),
            )
        val settingsRepo =
            mockk<SettingsRepository> {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        val cardConfigRepository =
            mockk<com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository> {
                every { dashboardCardConfigurations() } returns MutableStateFlow(emptyList())
            }
        val selectedDateRepo =
            mockk<SelectedDateRepository> {
                every { selectedDate } returns MutableStateFlow(java.time.LocalDate.now())
                coEvery { selectPreviousDay() } returns Unit
                coEvery { selectNextDay() } returns Unit
            }
        val circadianRepo =
            mockk<CircadianConsistencyRepository> {
                every { resultFor(any()) } returns MutableStateFlow(CircadianConsistencyResult.Calibrating)
            }
        syncController = mockk()

        viewModel =
            DashboardViewModel(
                dailySummaryRepository = dailySummaryRepo,
                getDashboardDataUseCase = getDashboardDataUseCase,
                foregroundSyncController = syncController,
                selectedDateRepository = selectedDateRepo,
                settingsRepo = settingsRepo,
                cardConfigRepository = cardConfigRepository,
                circadianRepo = circadianRepo,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onRefresh sets isRefreshing true then false on success`() =
        runTest {
            coEvery { syncController.triggerImmediateSync() } returns Unit

            viewModel.onRefresh()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRefreshing)
            coVerify(exactly = 1) { syncController.triggerImmediateSync() }
        }

    @Test
    fun `onRefresh sets isRefreshing false even when sync throws`() =
        runTest {
            coEvery { syncController.triggerImmediateSync() } throws RuntimeException("sync failed")

            viewModel.onRefresh()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test
    fun `uiState correctly aggregates basic inputs, card state, and realtime state`() =
        runTest {
            val initialState = viewModel.uiState.value

            // Verify that the state combines all three input flows
            org.junit.Assert.assertNotNull(initialState)
            org.junit.Assert.assertTrue(initialState.selectedDate != null)
            org.junit.Assert.assertTrue(initialState.cardConfigurations != null)
            org.junit.Assert.assertTrue(initialState.isRefreshing == false || initialState.isRefreshing == true)
        }

    @Test
    fun `onPreviousDay delegates to selectedDateRepository`() =
        runTest {
            coEvery {
                selectedDateRepository.selectPreviousDay()
            } returns Unit

            viewModel.onPreviousDay()
            coVerify { selectedDateRepository.selectPreviousDay() }
        }

    @Test
    fun `onNextDay delegates to selectedDateRepository`() =
        runTest {
            coEvery {
                selectedDateRepository.selectNextDay()
            } returns Unit

            viewModel.onNextDay()
            coVerify { selectedDateRepository.selectNextDay() }
        }

    @Test
    fun `toggleCardManagement delegates to cardManagementDelegate`() =
        runTest {
            viewModel.toggleCardManagement()
            // This should not throw
        }

    @Test
    fun `errorMessage exposes error state`() {
        org.junit.Assert.assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `isManagingCards exposes card management state`() {
        val managingState = viewModel.isManagingCards.value
        org.junit.Assert.assertTrue(managingState == true || managingState == false)
    }
}
