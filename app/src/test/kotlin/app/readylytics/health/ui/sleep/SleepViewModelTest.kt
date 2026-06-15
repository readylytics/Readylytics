package app.readylytics.health.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.ui.common.TimeRange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences(goalSleepHours = 8f))

        every { dailySummaryRepository.observeSince(any()) } returns flowOf(emptyList())
        every { sleepSessionRepository.observeSince(any()) } returns flowOf(emptyList())
        every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)
    }

    @After
    fun tearDown() =
        runTest(testDispatcher) {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
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
            assertEquals(8f, state.goalSleepHours, 0.001f)
            assertEquals(7, state.trendStartOffsetPoints.size)
            assertEquals(7, state.trendDurationSpanPoints.size)
            assertEquals(7, state.trendActualDurationPoints.size)
        }

    @Test
    fun `ui state updates when sleep goal preference changes`() =
        runTest(testDispatcher) {
            val prefsFlow = MutableStateFlow(UserPreferences(goalSleepHours = 7.5f))
            every { settingsRepo.userPreferences } returns prefsFlow

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            var state = viewModel.uiState.first { !it.isLoading }
            assertEquals(7.5f, state.goalSleepHours, 0.001f)

            prefsFlow.value = UserPreferences(goalSleepHours = 9f)
            testDispatcher.scheduler.advanceUntilIdle()

            state = viewModel.uiState.first { !it.isLoading && it.goalSleepHours == 9f }
            assertEquals(9f, state.goalSleepHours, 0.001f)
        }

    @Test
    fun `ui state exposes sleep time gauge data from current session and sleep goal`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val selectedDate = LocalDate.of(2026, 6, 11)
            val selectedMidnightMs =
                selectedDate
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        selectedDate
                            .minusDays(1)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime =
                        selectedDate
                            .atTime(6, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    durationMinutes = 510,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )

            coEvery { dailySummaryRepository.getByDate(selectedMidnightMs) } returns
                DailySummary(date = selectedDate, sleepDurationMinutes = 480)
            every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(session)
            every { sleepSessionRepository.observeSessionStages(session.id) } returns flowOf(emptyList())
            every { settingsRepo.userPreferences } returns flowOf(UserPreferences(goalSleepHours = 8f))

            viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.first { !it.isLoading && it.latestSession != null }
            val gaugeData = state.sleepTimeGaugeData
            assertEquals(0.5f, gaugeData.progress!!, 0.001f)
            assertEquals("8h", gaugeData.displayText)
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
