package com.gregor.lauritz.healthdashboard.ui.rhr

import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class RestingHrDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: RestingHrDetailViewModel
    private lateinit var repository: DailySummaryRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository =
            mockk {
                every { observeByDate(any()) } returns MutableStateFlow(null)
                every { observeSince(any()) } returns MutableStateFlow(emptyList())
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        selectedDateRepo = SelectedDateRepository()
    }

    private fun createViewModel(): RestingHrDetailViewModel =
        RestingHrDetailViewModel(
            dailySummaryRepository = repository,
            selectedDateRepository = selectedDateRepo,
            settingsRepo = settingsRepo,
        )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedRange)
            assertEquals(emptyList<com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint>(), state.dailyRhr)
        }

    @Test
    fun `onRangeSelected updates range`() =
        runTest {
            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
        }

    @Test
    fun `uiState updates when repository emits data`() =
        runTest {
            val today = LocalDate.now()
            val latestSummary = DailySummary(date = today, restingHeartRate = 60)

            every { repository.observeByDate(any()) } returns MutableStateFlow(latestSummary)
            every { repository.observeSince(any()) } returns MutableStateFlow(listOf(latestSummary))

            viewModel = createViewModel()

            // Trigger collection
            val state = viewModel.uiState.first { it.latestSummary != null }

            assertEquals(60, state.latestSummary?.restingHeartRate)
            assertEquals(7, state.dailyRhr.size) // Padded to 7 days
            val dataEntry = state.dailyRhr.last { it.value != null }
            assertEquals(60f, dataEntry.value)
            val nullCount = state.dailyRhr.count { it.value == null }
            assertEquals(6, nullCount)
        }
}
