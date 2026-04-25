package com.gregor.lauritz.healthdashboard.ui.rhr

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class RestingHrDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: RestingHrDetailViewModel
    private lateinit var dao: DailySummaryDao
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        dao = mockk {
            every { observeLatest() } returns MutableStateFlow(null)
            every { observeSince(any()) } returns MutableStateFlow(emptyList())
        }
        prefsRepo = mockk {
            every { userPreferences } returns MutableStateFlow(UserPreferences())
        }
        selectedDateRepo = SelectedDateRepository()

        viewModel = RestingHrDetailViewModel(
            dailySummaryDao = dao,
            selectedDateRepository = selectedDateRepo,
            prefsRepo = prefsRepo,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(TimeRange.SEVEN_DAYS, state.selectedRange)
        assertEquals(emptyList<com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint>(), state.dailyRhr)
    }

    @Test
    fun `onRangeSelected updates range`() = runTest {
        viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
        val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
        assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
    }

    @Test
    fun `uiState updates when dao emits data`() = runTest {
        val today = LocalDate.now()
        val midnightMs = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val latestSummary = DailySummaryEntity(dateMidnightMs = midnightMs, restingHeartRate = 60)

        every { dao.observeLatest() } returns MutableStateFlow(latestSummary)
        every { dao.observeSince(any()) } returns MutableStateFlow(listOf(latestSummary))

        // Trigger collection
        val state = viewModel.uiState.first { it.latestSummary != null }

        assertEquals(60, state.latestSummary?.restingHeartRate)
        assertEquals(1, state.dailyRhr.size)
        assertEquals(60f, state.dailyRhr[0].value)
    }
}
