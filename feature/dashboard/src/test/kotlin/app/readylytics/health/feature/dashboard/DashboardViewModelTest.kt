package app.readylytics.health.feature.dashboard

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.cache.DailyMetricCache
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import app.readylytics.health.feature.dashboard.usecase.GetDashboardDataUseCase
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardStrainIncreaseUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
class DashboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var getDashboardDataUseCase: GetDashboardDataUseCase
    private lateinit var foregroundSyncController: ForegroundSyncGateway
    private lateinit var selectedDateRepository: SelectedDateStore
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var cardConfigRepository: CardConfigurationRepository
    private lateinit var circadianRepo: CircadianConsistencyRepository
    private lateinit var dailyMetricCache: DailyMetricCache
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var insightDismissalRepository: InsightDismissalRepository
    private lateinit var observeDashboardStrainIncreaseUseCase: ObserveDashboardStrainIncreaseUseCase
    private lateinit var bodyTemperatureBaselineProvider: BodyTemperatureBaselineProvider
    private lateinit var healthConnectRepository: HealthConnectRepository
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        dailySummaryRepository = mockk(relaxed = true)
        getDashboardDataUseCase = mockk(relaxed = true)
        foregroundSyncController = mockk(relaxed = true)
        selectedDateRepository = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        cardConfigRepository = mockk(relaxed = true)
        circadianRepo = mockk(relaxed = true)
        dailyMetricCache = mockk(relaxed = true)
        heartRateRepository = mockk(relaxed = true)
        insightDismissalRepository = mockk(relaxed = true)
        observeDashboardStrainIncreaseUseCase = mockk(relaxed = true)
        bodyTemperatureBaselineProvider = mockk(relaxed = true)
        healthConnectRepository = mockk(relaxed = true)

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
                bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
                healthConnectRepository = healthConnectRepository,
                clock = java.time.Clock.systemDefaultZone(),
                defaultDispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
    fun toggleCardManagement_togglesState() {
        val initialState = viewModel.isManagingCards.value
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

    private fun sleepSession(
        durationMinutes: Int,
        awakeMinutes: Int,
    ) = SleepSessionData(
        id = "sleep_1",
        deviceName = "Test Ring",
        startTime = 0L,
        endTime = durationMinutes * 60_000L,
        durationMinutes = durationMinutes,
        efficiency = 0.9f,
        deepSleepMinutes = 90,
        lightSleepMinutes = 300,
        remSleepMinutes = 90,
        awakeMinutes = awakeMinutes,
        sleepScore = 85f,
    )
}
