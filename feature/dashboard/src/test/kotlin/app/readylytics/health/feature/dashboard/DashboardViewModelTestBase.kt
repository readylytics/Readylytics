package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.cache.DailyMetricCache
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionChecker
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.InsightDismissalRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.scoring.domain.airecommendation.DailyPromptData
import app.readylytics.health.core.scoring.domain.airecommendation.GetDailyPromptDataUseCase
import app.readylytics.health.core.scoring.domain.airecommendation.LoadStatePromptData
import app.readylytics.health.core.scoring.domain.airecommendation.TodayPromptData
import app.readylytics.health.core.scoring.domain.airecommendation.WorkoutPatternSummary
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.feature.dashboard.usecase.GetCurrentResidualFatigueUseCase
import app.readylytics.health.feature.dashboard.usecase.GetDashboardDataUseCase
import app.readylytics.health.feature.dashboard.usecase.LiveResidualFatigue
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardRasIncreaseUseCase
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardStrainIncreaseUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Shared mock harness for the DashboardViewModel test classes. Extracted so the suite could be
 * split by concern: the single class had grown past detekt's LargeClass threshold.
 */
abstract class DashboardViewModelTestBase {
    protected val testDispatcher = StandardTestDispatcher()
    protected lateinit var dailySummaryRepository: DailySummaryRepository
    protected lateinit var getDashboardDataUseCase: GetDashboardDataUseCase
    protected lateinit var foregroundSyncController: ForegroundSyncGateway
    protected lateinit var selectedDateRepository: SelectedDateStore
    protected lateinit var settingsRepo: UserPreferencesReader
    protected lateinit var cardConfigRepository: CardConfigurationRepository
    protected lateinit var circadianRepo: CircadianConsistencyRepository
    protected lateinit var dailyMetricCache: DailyMetricCache
    protected lateinit var heartRateRepository: HeartRateRepository
    protected lateinit var insightDismissalRepository: InsightDismissalRepository
    protected lateinit var observeDashboardStrainIncreaseUseCase: ObserveDashboardStrainIncreaseUseCase
    protected lateinit var observeDashboardRasIncreaseUseCase: ObserveDashboardRasIncreaseUseCase
    protected lateinit var getDailyPromptDataUseCase: GetDailyPromptDataUseCase
    protected lateinit var getCurrentResidualFatigueUseCase: GetCurrentResidualFatigueUseCase
    protected lateinit var fatigueTicker: DashboardFatigueTicker
    protected lateinit var bodyTemperatureBaselineProvider: BodyTemperatureBaselineProvider
    protected lateinit var permissionChecker: HealthConnectPermissionChecker
    protected lateinit var viewModel: DashboardViewModel

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
        observeDashboardRasIncreaseUseCase = mockk(relaxed = true)
        getDailyPromptDataUseCase = mockk(relaxed = true)
        getCurrentResidualFatigueUseCase = mockk(relaxed = true)
        coEvery { getCurrentResidualFatigueUseCase(any(), any()) } returns LiveResidualFatigue.NotApplicable
        // A single-bucket flow, never the production unbounded delay loop: that would keep the
        // StandardTestDispatcher permanently non-idle and hang every advanceUntilIdle() below.
        fatigueTicker = mockk()
        every { fatigueTicker.minuteBuckets() } returns flowOf(0L)
        bodyTemperatureBaselineProvider = mockk(relaxed = true)
        permissionChecker = mockk(relaxed = true)

        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The default wiring, shared by [setUp] and [configureDashboardFlows]. */
    protected fun buildViewModel(): DashboardViewModel =
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
            permissionChecker = permissionChecker,
            clock = java.time.Clock.systemDefaultZone(),
            defaultDispatcher = testDispatcher,
        )

    protected fun configureDashboardFlows(
        isSyncing: MutableStateFlow<Boolean>,
        recalcProgress: MutableStateFlow<RecalcProgress?>,
        summary: DailySummary?,
    ): LocalDate {
        val selectedDate = LocalDate.of(2026, 7, 29)
        val preferences = UserPreferences(scoringZoneId = "UTC")
        every { selectedDateRepository.selectedDate } returns MutableStateFlow(selectedDate)
        every { selectedDateRepository.earliestDate } returns MutableStateFlow(selectedDate.minusDays(30))
        every { settingsRepo.userPreferences } returns MutableStateFlow(preferences)
        every { dailySummaryRepository.observeByDate(any()) } returns flowOf(summary)
        every { dailySummaryRepository.observeSince(any()) } returns flowOf(listOfNotNull(summary))
        every { dailySummaryRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)
        every { cardConfigRepository.dashboardCardConfigurations() } returns flowOf(emptyList())
        every { circadianRepo.resultFor(any()) } returns flowOf(CircadianConsistencyResult.MissingData)
        every { insightDismissalRepository.observeForDate(any()) } returns flowOf(emptySet())
        every { heartRateRepository.observeAggregateByTimeRange(any(), any()) } returns flowOf(null)
        every { foregroundSyncController.isSyncing } returns isSyncing
        every { foregroundSyncController.recalcProgress } returns recalcProgress
        every { observeDashboardStrainIncreaseUseCase.invoke(any(), any()) } returns flowOf(0.23f)
        every { observeDashboardRasIncreaseUseCase.invoke(any(), any()) } returns flowOf(null)
        every { bodyTemperatureBaselineProvider.observeBaseline(any()) } returns flowOf(null)
        coEvery { permissionChecker.hasBodyTemperaturePermission() } returns true
        every {
            getDashboardDataUseCase.invoke(
                summary = any(),
                prefs = any(),
                date = any(),
                lastSleepSession = any(),
                rasSummaries = any(),
                circadianResult = any(),
                heartRateSummary = any(),
                todayStrainIncrease = any(),
                todayRasIncrease = any(),
                bodyTempBaseline = any(),
            )
        } returns
            Result.success(
                GetDashboardDataUseCase.DashboardCards(
                    cardDataMap = emptyMap(),
                    rasDailyBreakdown = emptyList(),
                ),
            )
        viewModel = buildViewModel()
        return selectedDate
    }

    protected fun sleepSession(
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

    protected fun promptData(): DailyPromptData =
        DailyPromptData(
            date = LocalDate.of(2026, 8, 9),
            physiologyProfile = null,
            calibrationPhase = null,
            baselineObservationCount = null,
            isCalibrating = true,
            activeTrainingLoadSource = "Workout only",
            everydayLoadConfidence = null,
            advisorDataConfidence = null,
            today =
                TodayPromptData(
                    readinessScore = null,
                    readinessBand = null,
                    restorationScore = null,
                    hrvBaseline = null,
                    hrvMuMssd = null,
                    hrvSigmaMssd = null,
                    restingHeartRate = null,
                    restingHrRatio = null,
                    rhrSigma = null,
                    nocturnalHrv = null,
                    zLnHrv = null,
                    zRhr = null,
                    baselineCalculatedAtDate = null,
                    todayCompletedWorkouts = 0,
                    todayTrimp = null,
                    todayTrainingMinutes = null,
                    dataCurrentUntil = null,
                ),
            yesterdaySleep = null,
            yesterdayWorkouts = emptyList(),
            loadState =
                LoadStatePromptData(
                    acuteLoad = null,
                    chronicLoad = null,
                    strainRatio = null,
                    loadScore = null,
                    loadContext = null,
                    totalRasWorkoutOnly = null,
                    totalRasEverydayHr = null,
                    everydayCoverageMinutes = null,
                ),
            activeRecoveryFlags = emptyList(),
            workoutPattern =
                WorkoutPatternSummary(
                    lookbackMonths = 3,
                    totalWorkoutsInWindow = 0,
                    exerciseTypeBreakdown = emptyList(),
                    restDaysPerWeekAverage = 7f,
                    mostRecentRestDayGapDays = 0,
                    currentConsecutiveTrainingDayStreak = 0,
                ),
        )
}
