package com.gregor.lauritz.healthdashboard.ui.steps

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StepDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: StepDetailViewModel
    private lateinit var dao: DailySummaryDao
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        dao =
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

    private fun createViewModel(): StepDetailViewModel =
        StepDetailViewModel(
            dailySummaryDao = dao,
            selectedDateRepository = selectedDateRepo,
            settingsRepo = settingsRepo,
        )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedRange)
            assertEquals(emptyList<com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint>(), state.dailySteps)
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
    fun `uiState updates when dao emits data`() =
        runTest {
            val today = LocalDate.now()
            val midnightMs = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val latestSummary = DailySummaryEntity(dateMidnightMs = midnightMs, stepCount = 8500)

            every { dao.observeByDate(any()) } returns MutableStateFlow(latestSummary)
            every { dao.observeSince(any()) } returns MutableStateFlow(listOf(latestSummary))

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.latestSummary != null }

            assertEquals(8500, state.latestSummary?.stepCount)
            assertEquals(7, state.dailySteps.size)
            val dataEntry = state.dailySteps.last { it.value != null }
            assertEquals(8500f, dataEntry.value)
            val nullCount = state.dailySteps.count { it.value == null }
            assertEquals(6, nullCount)
        }
}
