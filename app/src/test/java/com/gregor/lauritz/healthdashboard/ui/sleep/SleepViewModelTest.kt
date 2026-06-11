package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.DailyMetricsRepository
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
class SleepViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val dailySummaryRepository: DailySummaryRepository = mockk(relaxed = true)
    private val dailyMetricsRepository: DailyMetricsRepository = mockk(relaxed = true)
    private val sleepSessionRepository: SleepSessionRepository = mockk(relaxed = true)
    private val heartRateRepository: HeartRateRepository = mockk(relaxed = true)
    private val settingsRepo: SettingsRepository = mockk(relaxed = true)
    private val selectedDateRepository: SelectedDateRepository = mockk(relaxed = true)
    private val circadianRepo: CircadianConsistencyRepository = mockk(relaxed = true)
    private val foregroundSyncController: ForegroundSyncController = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = mockk(relaxed = true)

    private val selectedDateFlow = MutableStateFlow(LocalDate.of(2026, 6, 11))
    private lateinit var viewModel: SleepViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { selectedDateRepository.selectedDate } returns selectedDateFlow
        every { selectedDateRepository.earliestDate } returns MutableStateFlow(null)
        every { circadianRepo.resultFor(any()) } returns flowOf(CircadianConsistencyResult.Calibrating)
        every { foregroundSyncController.isSyncing } returns MutableStateFlow(false)
        every { dailyMetricsRepository.observeByDate(any()) } returns flowOf(null)

        every { dailySummaryRepository.observeSince(any()) } returns flowOf(emptyList())
        every { sleepSessionRepository.observeSince(any()) } returns flowOf(emptyList())
        every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        SleepViewModel(
            dailySummaryRepository = dailySummaryRepository,
            dailyMetricsRepository = dailyMetricsRepository,
            sleepSessionRepository = sleepSessionRepository,
            heartRateRepository = heartRateRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepository,
            circadianRepo = circadianRepo,
            foregroundSyncController = foregroundSyncController,
            savedStateHandle = savedStateHandle,
        )

    @Test
    fun `initial state has default trend range and empty points`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading }
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedTrendRange)
            assertEquals(7, state.trendStartOffsetPoints.size)
            assertEquals(7, state.trendDurationSpanPoints.size)
            assertEquals(7, state.trendActualDurationPoints.size)
        }

    @Test
    fun `onTrendRangeSelected updates selected trend range`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onTrendRangeSelected(TimeRange.THIRTY_DAYS)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.selectedTrendRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedTrendRange)
            assertEquals(30, state.trendStartOffsetPoints.size)
        }

    @Test
    fun `trend data points are correctly calculated from sleep sessions`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        LocalDate
                            .of(2026, 6, 10)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        LocalDate
                            .of(2026, 6, 11)
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )

            every { sleepSessionRepository.observeSince(any()) } returns flowOf(listOf(session))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state =
                viewModel.uiState.first {
                    !it.isLoading &&
                        it.trendStartOffsetPoints.any { p -> p.value != null }
                }

            val startPoint = state.trendStartOffsetPoints.last()
            val spanPoint = state.trendDurationSpanPoints.last()
            val actualPoint = state.trendActualDurationPoints.last()

            assertEquals(6, startPoint.dayOffset)
            assertEquals(10f, startPoint.value!!, 0.01f)
            assertEquals(8f, spanPoint.value!!, 0.01f)
            assertEquals(7.5f, actualPoint.value!!, 0.01f)
        }

    @Test
    fun `trend data points are padded with null values when no sleep sessions exist`() =
        runTest(testDispatcher) {
            every { sleepSessionRepository.observeSince(any()) } returns flowOf(emptyList())

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading }
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedTrendRange)
            assertEquals(7, state.trendStartOffsetPoints.size)
            assertEquals(true, state.trendStartOffsetPoints.all { it.value == null })
            assertEquals(true, state.trendDurationSpanPoints.all { it.value == null })
            assertEquals(true, state.trendActualDurationPoints.all { it.value == null })
        }
}
