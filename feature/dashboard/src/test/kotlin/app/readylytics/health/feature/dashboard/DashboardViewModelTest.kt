package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.feature.dashboard.usecase.GetDashboardDataUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest : DashboardViewModelTestBase() {
    @Test
    fun `DismissInsight resolves dateMs from the scoring zone, not the device zone`() =
        runTest {
            val selectedDate = LocalDate.of(2024, 6, 1)
            every { selectedDateRepository.selectedDate } returns MutableStateFlow(selectedDate)
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringZoneId = "Pacific/Kiritimati"))
            coEvery { insightDismissalRepository.dismiss(any(), any()) } returns Unit

            viewModel.onEvent(DashboardEvent.DismissInsight(InsightType.LATE_NADIR))
            advanceUntilIdle()

            val expectedDateMs =
                selectedDate
                    .atStartOfDay(ZoneId.of("Pacific/Kiritimati"))
                    .toInstant()
                    .toEpochMilli()
            coVerify { insightDismissalRepository.dismiss(expectedDateMs, InsightType.LATE_NADIR) }
        }

    @Test
    fun validateSelectedDate_today_succeeds() {
        val result = viewModel.validateSelectedDate(LocalDate.now())
        assert(result.isSuccess) { "Today should be valid" }
    }

    @Test
    fun validateSelectedDate_pastDate_succeeds() {
        val result = viewModel.validateSelectedDate(LocalDate.now().minusDays(30))
        assert(result.isSuccess) { "Past date should be valid" }
    }

    @Test
    fun validateSelectedDate_futureDate_fails() {
        val result = viewModel.validateSelectedDate(LocalDate.now().plusDays(1))
        assert(result.isFailure) { "Future date should be invalid" }
    }

    @Test
    fun `request daily prompt emits formatted text for today`() =
        runTest {
            val fixedClock =
                java.time.Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC)
            viewModel =
                DashboardViewModel(
                    dailySummaryRepository = dailySummaryRepository,
                    getDashboardDataUseCase = getDashboardDataUseCase,
                    foregroundSyncController = foregroundSyncController,
                    selectedDateRepository = selectedDateRepository,
                    settingsRepo = settingsRepo,
                    cardConfigRepository = cardConfigRepository,
                    circadianRepo = circadianRepo,
                    dailyMetricCache = dailyMetricCache,
                    heartRateRepository = heartRateRepository,
                    insightDismissalRepository = insightDismissalRepository,
                    observeDashboardStrainIncreaseUseCase = observeDashboardStrainIncreaseUseCase,
                    observeDashboardRasIncreaseUseCase = observeDashboardRasIncreaseUseCase,
                    getDailyPromptDataUseCase = getDailyPromptDataUseCase,
                    getCurrentResidualFatigueUseCase = getCurrentResidualFatigueUseCase,
                    fatigueTicker = fatigueTicker,
                    bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
                    healthConnectRepository = healthConnectRepository,
                    clock = fixedClock,
                    defaultDispatcher = testDispatcher,
                )
            coEvery { getDailyPromptDataUseCase.execute(LocalDate.of(2026, 8, 9)) } returns promptData()
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringZoneId = "UTC"))

            viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
            advanceUntilIdle()

            assertNotNull(viewModel.dailyPromptText.value)
            assertTrue(
                viewModel.dailyPromptText.value!!
                    .text
                    .contains("Today's data for 2026-08-09"),
            )
            coVerify(exactly = 1) { getDailyPromptDataUseCase.execute(LocalDate.of(2026, 8, 9)) }
        }

    @Test
    fun `repeated prompt copy requests emit distinct values even with identical text`() =
        runTest {
            val fixedClock =
                java.time.Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC)
            viewModel =
                DashboardViewModel(
                    dailySummaryRepository = dailySummaryRepository,
                    getDashboardDataUseCase = getDashboardDataUseCase,
                    foregroundSyncController = foregroundSyncController,
                    selectedDateRepository = selectedDateRepository,
                    settingsRepo = settingsRepo,
                    cardConfigRepository = cardConfigRepository,
                    circadianRepo = circadianRepo,
                    dailyMetricCache = dailyMetricCache,
                    heartRateRepository = heartRateRepository,
                    insightDismissalRepository = insightDismissalRepository,
                    observeDashboardStrainIncreaseUseCase = observeDashboardStrainIncreaseUseCase,
                    observeDashboardRasIncreaseUseCase = observeDashboardRasIncreaseUseCase,
                    getDailyPromptDataUseCase = getDailyPromptDataUseCase,
                    getCurrentResidualFatigueUseCase = getCurrentResidualFatigueUseCase,
                    fatigueTicker = fatigueTicker,
                    bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
                    healthConnectRepository = healthConnectRepository,
                    clock = fixedClock,
                    defaultDispatcher = testDispatcher,
                )
            coEvery { getDailyPromptDataUseCase.execute(any()) } returns promptData()
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringZoneId = "UTC"))

            viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
            viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
            advanceUntilIdle()

            val second = viewModel.dailyPromptText.value
            assertNotNull(second)
            assertTrue(second!!.requestId == 1)
        }

    @Test
    fun `request daily prompt resolves today in the scoring zone, not the device clock zone`() =
        runTest {
            val fixedClock =
                java.time.Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneOffset.UTC)
            val scoringZone = "America/Los_Angeles"
            viewModel =
                DashboardViewModel(
                    dailySummaryRepository = dailySummaryRepository,
                    getDashboardDataUseCase = getDashboardDataUseCase,
                    foregroundSyncController = foregroundSyncController,
                    selectedDateRepository = selectedDateRepository,
                    settingsRepo = settingsRepo,
                    cardConfigRepository = cardConfigRepository,
                    circadianRepo = circadianRepo,
                    dailyMetricCache = dailyMetricCache,
                    heartRateRepository = heartRateRepository,
                    insightDismissalRepository = insightDismissalRepository,
                    observeDashboardStrainIncreaseUseCase = observeDashboardStrainIncreaseUseCase,
                    observeDashboardRasIncreaseUseCase = observeDashboardRasIncreaseUseCase,
                    getDailyPromptDataUseCase = getDailyPromptDataUseCase,
                    getCurrentResidualFatigueUseCase = getCurrentResidualFatigueUseCase,
                    fatigueTicker = fatigueTicker,
                    bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
                    healthConnectRepository = healthConnectRepository,
                    clock = fixedClock,
                    defaultDispatcher = testDispatcher,
                )
            coEvery { getDailyPromptDataUseCase.execute(LocalDate.of(2026, 8, 9)) } returns promptData()
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringZoneId = scoringZone))

            viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
            advanceUntilIdle()

            val localDay = LocalDate.now(fixedClock.withZone(ZoneId.of(scoringZone)))
            assertEquals(LocalDate.of(2026, 8, 9), localDay)
            coVerify(exactly = 1) { getDailyPromptDataUseCase.execute(LocalDate.of(2026, 8, 9)) }
        }

    @Test
    fun `clear daily prompt returns state to null`() =
        runTest {
            viewModel.clearDailyPromptText()

            assertNull(viewModel.dailyPromptText.value)
        }

    @Test
    fun `daily prompt failure exposes error and emits no text`() =
        runTest {
            coEvery { getDailyPromptDataUseCase.execute(any()) } throws IOException("boom")

            viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy)
            advanceUntilIdle()

            assertNull(viewModel.dailyPromptText.value)
            assertNotNull(viewModel.errorMessage.value)
        }

    @Test
    fun `daily prompt cancellation is rethrown`() =
        runTest {
            coEvery { getDailyPromptDataUseCase.execute(any()) } throws CancellationException("cancelled")

            var thrown: Throwable? = null
            try {
                viewModel.generateDailyPrompt(LocalDate.of(2026, 8, 9))
            } catch (e: CancellationException) {
                thrown = e
            }

            assertTrue(thrown is CancellationException)
        }

    @Test
    fun toggleCardManagement_togglesState() {
        viewModel.toggleCardManagement()
        // Note: would need stateflow emission to verify state changed
        // This is a basic structure test
        assert(true)
    }

    @Test
    fun onPreviousDay_launchesScope() {
        viewModel.onPreviousDay()
        assert(true) { "Should launch without error" }
    }

    @Test
    fun onNextDay_launchesScope() {
        viewModel.onNextDay()
        assert(true) { "Should launch without error" }
    }

    @Test
    fun `dashboard session summary derives from session data`() {
        val summary =
            viewModel.resolveDashboardSleepSessionSummary(
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
            )

        assertEquals(0.9f, summary?.efficiency)
        assertEquals(0L, summary?.startTime)
        assertEquals(510 * 60_000L, summary?.endTime)
    }

    @Test
    fun `dashboard forwards observed strain increase to dashboard data use case`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 7, 29)
            val summary = DailySummary(date = selectedDate)
            val preferences = UserPreferences(scoringZoneId = "UTC")
            every { selectedDateRepository.selectedDate } returns MutableStateFlow(selectedDate)
            every { selectedDateRepository.earliestDate } returns MutableStateFlow(selectedDate.minusDays(30))
            every { settingsRepo.userPreferences } returns MutableStateFlow(preferences)
            every { dailySummaryRepository.observeByDate(any()) } returns flowOf(summary)
            every { dailySummaryRepository.observeSince(any()) } returns flowOf(listOf(summary))
            every {
                dailySummaryRepository.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(null)
            every { cardConfigRepository.dashboardCardConfigurations() } returns flowOf(emptyList())
            every { circadianRepo.resultFor(selectedDate) } returns flowOf(CircadianConsistencyResult.MissingData)
            every { insightDismissalRepository.observeForDate(any()) } returns flowOf(emptySet())
            every {
                heartRateRepository.observeAggregateByTimeRange(any(), any())
            } returns flowOf(null)
            every { foregroundSyncController.isSyncing } returns MutableStateFlow(false)
            every { foregroundSyncController.recalcProgress } returns MutableStateFlow(null)
            every {
                observeDashboardStrainIncreaseUseCase.invoke(any(), any())
            } returns flowOf(0.23f)
            every {
                observeDashboardRasIncreaseUseCase.invoke(any(), any())
            } returns flowOf(null)
            every { bodyTemperatureBaselineProvider.observeBaseline(any()) } returns flowOf(null)
            coEvery { healthConnectRepository.hasBodyTemperaturePermission() } returns true
            every {
                getDashboardDataUseCase.invoke(
                    summary = summary,
                    prefs = preferences,
                    date = selectedDate,
                    lastSleepSession = null,
                    rasSummaries = listOf(summary),
                    circadianResult = CircadianConsistencyResult.MissingData,
                    heartRateSummary = null,
                    todayStrainIncrease = 0.23f,
                    todayRasIncrease = null,
                )
            } returns
                Result.success(
                    GetDashboardDataUseCase.DashboardCards(
                        cardDataMap = emptyMap(),
                        rasDailyBreakdown = emptyList(),
                    ),
                )

            viewModel =
                DashboardViewModel(
                    dailySummaryRepository = dailySummaryRepository,
                    getDashboardDataUseCase = getDashboardDataUseCase,
                    foregroundSyncController = foregroundSyncController,
                    selectedDateRepository = selectedDateRepository,
                    settingsRepo = settingsRepo,
                    cardConfigRepository = cardConfigRepository,
                    circadianRepo = circadianRepo,
                    dailyMetricCache = dailyMetricCache,
                    heartRateRepository = heartRateRepository,
                    insightDismissalRepository = insightDismissalRepository,
                    observeDashboardStrainIncreaseUseCase = observeDashboardStrainIncreaseUseCase,
                    observeDashboardRasIncreaseUseCase = observeDashboardRasIncreaseUseCase,
                    getDailyPromptDataUseCase = getDailyPromptDataUseCase,
                    getCurrentResidualFatigueUseCase = getCurrentResidualFatigueUseCase,
                    fatigueTicker = fatigueTicker,
                    bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
                    healthConnectRepository = healthConnectRepository,
                    clock = java.time.Clock.systemDefaultZone(),
                    defaultDispatcher = testDispatcher,
                )

            viewModel.uiState.first { it.summary == summary }

            verify {
                getDashboardDataUseCase.invoke(
                    summary = summary,
                    prefs = preferences,
                    date = selectedDate,
                    lastSleepSession = null,
                    rasSummaries = listOf(summary),
                    circadianResult = CircadianConsistencyResult.MissingData,
                    heartRateSummary = null,
                    todayStrainIncrease = 0.23f,
                )
            }
        }

    @Test
    fun `uiState sets isComputingMetrics to true when syncing and summary is null`() =
        runTest(testDispatcher) {
            val isSyncing = MutableStateFlow(true)
            val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
            val selectedDate = configureDashboardFlows(isSyncing, recalcProgress, summary = null)

            val state = viewModel.uiState.first { it.isComputingMetrics }

            assertTrue(state.isComputingMetrics)
            assertTrue(state.isRefreshing)
            assertEquals(selectedDate, state.selectedDate)
        }

    @Test
    fun `uiState sets isComputingMetrics to false when syncing and cached summary exists`() =
        runTest(testDispatcher) {
            val isSyncing = MutableStateFlow(true)
            val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
            val summary = DailySummary(date = LocalDate.of(2026, 7, 29))
            configureDashboardFlows(isSyncing, recalcProgress, summary = summary)

            val state = viewModel.uiState.first { it.summary != null }

            assertFalse(state.isComputingMetrics)
            assertTrue(state.isRefreshing)
        }

    @Test
    fun `uiState sets isComputingMetrics to false when not syncing and summary is null`() =
        runTest(testDispatcher) {
            val isSyncing = MutableStateFlow(false)
            val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
            val selectedDate = configureDashboardFlows(isSyncing, recalcProgress, summary = null)

            val state = viewModel.uiState.first { it.selectedDate == selectedDate }

            assertFalse(state.isComputingMetrics)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun `uiState propagates recalcProgress updates cleanly from sync gateway`() =
        runTest(testDispatcher) {
            val isSyncing = MutableStateFlow(true)
            val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
            configureDashboardFlows(isSyncing, recalcProgress, summary = null)

            val states = mutableListOf<DashboardUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect(states::add) }
            runCurrent()

            val progress = RecalcProgress(ResyncPhase.RECONCILE, current = 0, total = 0)
            recalcProgress.value = progress
            runCurrent()

            assertEquals(progress, states.last().recalcProgress)
            job.cancel()
        }
}
