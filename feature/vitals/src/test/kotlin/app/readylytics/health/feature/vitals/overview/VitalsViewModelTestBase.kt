package app.readylytics.health.feature.vitals.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartId
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
abstract class VitalsViewModelTestBase {
    protected val testDispatcher = StandardTestDispatcher()
    protected val summaries = MutableStateFlow<List<DailySummary>>(emptyList())
    protected val selectedDateFlow = MutableStateFlow(LocalDate.now())
    protected val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    protected val syncing = MutableStateFlow(false)
    protected val bodyTemperatureBaseline = MutableStateFlow<Float?>(36.5f)
    protected val vitalsCardConfigs =
        MutableStateFlow<List<CardConfiguration>>(
            listOf(
                CardConfiguration(CardId.RESTING_HR, isVisible = true, position = 0),
                CardConfiguration(CardId.HRV, isVisible = true, position = 1),
            ),
        )
    protected val vitalsChartConfigs =
        MutableStateFlow<List<VitalsChartConfiguration>>(
            listOf(
                VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 0),
                VitalsChartConfiguration(VitalsChartId.RHR_TREND, isVisible = true, position = 1),
                VitalsChartConfiguration(VitalsChartId.SPO2_TREND, isVisible = true, position = 2),
                VitalsChartConfiguration(VitalsChartId.BODY_TEMP_TREND, isVisible = true, position = 3),
            ),
        )
    protected val metricsByDate = mutableMapOf<LocalDate, MutableStateFlow<DailyMetrics?>>()
    protected val customMetricsFlowsByDate = mutableMapOf<LocalDate, Flow<DailyMetrics?>>()
    protected val settingsRepo = FakeUserPreferencesReader()

    protected lateinit var viewModel: VitalsViewModel

    protected val dailySummaryRepository =
        mockk<DailySummaryRepository> {
            every { observeSince(any()) } returns summaries
            every { observeByDate(any()) } returns MutableStateFlow(null)
        }
    protected val dailyMetricsRepository =
        mockk<DailyMetricsRepository> {
            every { observeByDate(any()) } answers {
                val date = firstArg<LocalDate>()
                customMetricsFlowsByDate[date] ?: metricsFlow(date)
            }
        }
    protected val selectedDateStore =
        mockk<SelectedDateStore> {
            every { selectedDate } returns selectedDateFlow
            every { earliestDate } returns earliestDateFlow
            coEvery { updateSelectedDate(any()) } answers {
                selectedDateFlow.value = firstArg<LocalDate>()
            }
            coEvery { resetToToday() } answers {
                selectedDateFlow.value = LocalDate.now()
            }
            coEvery { advanceTodayIfNeeded() } returns Unit
            coEvery { selectPreviousDay() } answers {
                selectedDateFlow.value = selectedDateFlow.value.minusDays(1)
            }
            coEvery { selectNextDay() } answers {
                selectedDateFlow.value = selectedDateFlow.value.plusDays(1)
            }
        }
    protected val foregroundSyncGateway =
        mockk<ForegroundSyncGateway> {
            every { isSyncing } returns syncing
            every { recalcProgress } returns MutableStateFlow(null)
            every { syncCompletedEvent } returns MutableSharedFlow()
            coEvery { evaluateAndSync() } returns Unit
            coEvery { triggerImmediateSync() } returns Unit
            coEvery { triggerDailySync() } returns Unit
        }
    protected val bodyTemperatureBaselineProvider =
        mockk<BodyTemperatureBaselineProvider> {
            coEvery { getBaseline(any()) } answers { bodyTemperatureBaseline.value }
            every { observeBaseline(any()) } returns bodyTemperatureBaseline
        }

    protected val vitalsLayoutRepository =
        mockk<VitalsLayoutRepository> {
            every { vitalsCardConfigurations() } returns vitalsCardConfigs
            every { vitalsChartConfigurations() } returns vitalsChartConfigs
            coEvery { updateVitalsCardConfigurations(any()) } returns Unit
            coEvery { updateVitalsChartConfigurations(any()) } returns Unit
        }

    protected val healthConnectRepository =
        mockk<HealthConnectRepository> {
            coEvery { hasBodyTemperaturePermission() } returns true
            coEvery { hasOxygenSaturationPermission() } returns true
        }

    protected fun createViewModel() =
        VitalsViewModel(
            dailySummaryRepository = dailySummaryRepository,
            dailyMetricsRepository = dailyMetricsRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateStore,
            foregroundSyncController = foregroundSyncGateway,
            savedStateHandle = SavedStateHandle(),
            bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
            vitalsLayoutRepository = vitalsLayoutRepository,
            healthConnectRepository = healthConnectRepository,
            ioDispatcher = testDispatcher,
        )

    @Before
    open fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepo.reset()
        summaries.value =
            listOf(
                summary(date = LocalDate.now(), hrv = 42, rhr = 51, spo2 = 96f),
                summary(date = LocalDate.now().minusDays(1), hrv = 40, rhr = 49, spo2 = 95f),
            )
        metricsByDate.clear()
        customMetricsFlowsByDate.clear()
        metricsFlow(LocalDate.now()).value =
            dailyMetrics(
                date = LocalDate.now(),
                hrv = 42,
                rhr = 51,
                hrvBaselineRounded = 41,
                rhrBaselineRounded = 48,
                rhrSnapshotRaw = 48f,
            )
        syncing.value = false
        bodyTemperatureBaseline.value = 36.5f
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }
        Dispatchers.resetMain()
    }

    protected fun summary(
        date: LocalDate,
        hrv: Int? = null,
        rhr: Int? = null,
        spo2: Float? = null,
        bodyTemp: Float? = null,
    ): DailySummary =
        DailySummary(
            date = date,
            nocturnalHrv = hrv,
            restingHeartRate = rhr,
            avgSleepingSpo2 = spo2,
            avgSleepingBodyTemp = bodyTemp,
            isCalibrating = false,
        )

    protected fun dailyMetrics(
        date: LocalDate,
        hrv: Int? = null,
        rhr: Int? = null,
        hrvBaselineRounded: Int? = null,
        rhrBaselineRounded: Int? = null,
        rhrSnapshotRaw: Float? = null,
    ): DailyMetrics =
        DailyMetrics(
            date = date,
            nocturnalHrvRounded = hrv,
            nocturnalRhrRounded = rhr,
            hrvBaselineRounded = hrvBaselineRounded,
            rhrBaselineRounded = rhrBaselineRounded,
            rhrSnapshotRaw = rhrSnapshotRaw,
        )

    protected fun metricsFlow(date: LocalDate): MutableStateFlow<DailyMetrics?> =
        metricsByDate.getOrPut(date) { MutableStateFlow(null) }

    protected class FakeUserPreferencesReader : UserPreferencesReader {
        private val preferences = MutableStateFlow(UserPreferences())
        override val userPreferences: Flow<UserPreferences> = preferences

        fun reset() {
            preferences.value = UserPreferences()
        }

        fun emitHrvThresholds(
            optimal: Float,
            warning: Float,
        ) {
            preferences.value =
                preferences.value.copy(
                    hrvOptimalThreshold = optimal,
                    hrvWarningThreshold = warning,
                )
        }

        fun emitAppTheme(appTheme: AppTheme) {
            preferences.value = preferences.value.copy(appTheme = appTheme)
        }

        fun emitUnitSystem(unitSystem: UnitSystem) {
            preferences.value = preferences.value.copy(unitSystem = unitSystem)
        }
    }
}
