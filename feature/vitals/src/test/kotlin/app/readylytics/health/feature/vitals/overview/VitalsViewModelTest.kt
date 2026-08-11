package app.readylytics.health.feature.vitals.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import app.readylytics.health.domain.util.UnitConverter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class VitalsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val summaries = MutableStateFlow<List<DailySummary>>(emptyList())
    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    private val syncing = MutableStateFlow(false)
    private val bodyTemperatureBaseline = MutableStateFlow<Float?>(36.5f)
    private val metricsByDate = mutableMapOf<LocalDate, MutableStateFlow<DailyMetrics?>>()
    private val customMetricsFlowsByDate = mutableMapOf<LocalDate, Flow<DailyMetrics?>>()
    private val settingsRepo = FakeUserPreferencesReader()

    private lateinit var viewModel: VitalsViewModel

    private val dailySummaryRepository =
        mockk<DailySummaryRepository> {
            every { observeSince(any()) } returns summaries
            every { observeByDate(any()) } returns MutableStateFlow(null)
        }
    private val dailyMetricsRepository =
        mockk<DailyMetricsRepository> {
            every { observeByDate(any()) } answers {
                val date = firstArg<LocalDate>()
                customMetricsFlowsByDate[date] ?: metricsFlow(date)
            }
        }
    private val selectedDateStore =
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
    private val foregroundSyncGateway =
        mockk<ForegroundSyncGateway> {
            every { isSyncing } returns syncing
            every { recalcProgress } returns MutableStateFlow(null)
            every { syncCompletedEvent } returns MutableSharedFlow()
            coEvery { evaluateAndSync() } returns Unit
            coEvery { triggerImmediateSync() } returns Unit
            coEvery { triggerDailySync() } returns Unit
        }
    private val bodyTemperatureBaselineProvider =
        mockk<BodyTemperatureBaselineProvider> {
            coEvery { getBaseline(any()) } answers { bodyTemperatureBaseline.value }
            every { observeBaseline(any()) } returns bodyTemperatureBaseline
        }

    private fun createViewModel() =
        VitalsViewModel(
            dailySummaryRepository = dailySummaryRepository,
            dailyMetricsRepository = dailyMetricsRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateStore,
            foregroundSyncController = foregroundSyncGateway,
            savedStateHandle = SavedStateHandle(),
            bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
            ioDispatcher = testDispatcher,
        )

    @Before
    fun setUp() {
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

    @Test
    fun `sync change preserves structurally equal chart series`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val before = viewModel.uiState.value
                assertFalse(before.isLoading)
                syncing.value = true
                advanceUntilIdle()
                val during = viewModel.uiState.value

                assertSame(before.chartSeries.hrv, during.chartSeries.hrv)
                assertSame(before.chartSeries.rhr, during.chartSeries.rhr)
                assertSame(before.chartSeries.spo2, during.chartSeries.spo2)
                assertSame(before.chartSeries.bodyTemp, during.chartSeries.bodyTemp)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `isRefreshing toggles independently of isLoading when data is present`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val before = viewModel.uiState.value
                assertFalse(before.isLoading)
                assertFalse(before.isRefreshing)

                syncing.value = true
                advanceUntilIdle()
                val during = viewModel.uiState.value
                assertFalse(during.isLoading)
                assertTrue(during.isRefreshing)

                syncing.value = false
                advanceUntilIdle()
                val after = viewModel.uiState.value
                assertFalse(after.isLoading)
                assertFalse(after.isRefreshing)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `isLoading stays true while syncing when no summary exists yet`() =
        runTest {
            summaries.value = emptyList()
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                syncing.value = true
                advanceUntilIdle()
                val state = viewModel.uiState.value
                assertTrue(state.isLoading)
                assertTrue(state.isRefreshing)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `isLoading stays false when historical chart data exists even if today's summary is missing`() =
        runTest {
            // Reproduces the once-per-day gap: today's summary hasn't landed yet (so
            // latestSummary is null), but yesterday's summary is already in the loaded range and
            // has real values -- the charts have historical data to show, so no skeleton should
            // appear during this sync.
            //
            // Note: the class-level `observeSince(any())` stub returns the same `summaries` flow
            // for every call regardless of the ms argument -- it does not filter by date the way
            // the real repository would. So merely trimming `summaries` down to yesterday's entry
            // is not enough to make `latestSummary` null: Vitals' "today" lookup
            // (`observeSince(todayMs).map { it.firstOrNull() }`) would still see yesterday's entry
            // as the list's first (and only) element. To faithfully reproduce "today's summary is
            // missing", override the today-scoped call specifically to return an empty list, while
            // the chart-range call (a different ms argument) keeps returning yesterday's data via
            // the existing generic stub.
            val zoneId = ZoneId.systemDefault()
            val todayMs =
                LocalDate
                    .now(zoneId)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            every { dailySummaryRepository.observeSince(match { it == todayMs }) } returns MutableStateFlow(emptyList())
            summaries.value =
                listOf(
                    summary(date = LocalDate.now().minusDays(1), hrv = 40, rhr = 49, spo2 = 95f),
                )
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                syncing.value = true
                advanceUntilIdle()
                val state = viewModel.uiState.value
                assertNull(state.latestSummary)
                assertFalse(state.isLoading)
                assertTrue(state.isRefreshing)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `threshold preference emission updates presentation and rebuilds chart series`() =
        runTest {
            var observeSinceCalls = 0
            every { dailySummaryRepository.observeSince(any()) } answers {
                observeSinceCalls += 1
                summaries
            }
            val today = LocalDate.now()
            summaries.value =
                listOf(
                    DailySummary(
                        date = today,
                        nocturnalHrv = 42,
                        restingHeartRate = 51,
                        avgSleepingSpo2 = 96f,
                        rhrBpm = 51f,
                        hrvMuMssd = 3.7f,
                        baselineCalculatedAtDate = today,
                        isCalibrating = false,
                    ),
                    DailySummary(
                        date = today.minusDays(1),
                        nocturnalHrv = 40,
                        restingHeartRate = 49,
                        avgSleepingSpo2 = 95f,
                        rhrBpm = 49f,
                        hrvMuMssd = 3.6f,
                        baselineCalculatedAtDate = today.minusDays(1),
                        isCalibrating = false,
                    ),
                )
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                viewModel.onRangeSelected(TimeRange.SIX_MONTHS)
                advanceUntilIdle()
                val before = viewModel.uiState.value
                val beforeObserveSinceCalls = observeSinceCalls
                assertFalse(before.isLoading)
                assertEquals(MetricStatus.NEUTRAL, before.presentation.hrv.status)
                assertTrue(before.chartSeries.historicalHrvZoneBands.isNotEmpty())
                settingsRepo.emitHrvThresholds(optimal = 0.95f, warning = 0.85f)
                advanceUntilIdle()
                val after = viewModel.uiState.value

                assertEquals(MetricStatus.OPTIMAL, after.presentation.hrv.status)
                assertTrue(
                    "HRV thresholds feed historical zone bands, so they must re-query chart data",
                    observeSinceCalls > beforeObserveSinceCalls,
                )
                assertTrue(before.chartSeries.hrv !== after.chartSeries.hrv)
                assertTrue(before.chartSeries.rhr !== after.chartSeries.rhr)
                assertTrue(before.chartSeries.spo2 !== after.chartSeries.spo2)
                assertTrue(before.chartSeries.bodyTemp !== after.chartSeries.bodyTemp)
                assertTrue(
                    "zone bands must recompute from the new thresholds",
                    before.chartSeries.historicalHrvZoneBands != after.chartSeries.historicalHrvZoneBands,
                )
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `chart-irrelevant preference emission does not rebuild chart series`() =
        runTest {
            var observeSinceCalls = 0
            every { dailySummaryRepository.observeSince(any()) } answers {
                observeSinceCalls += 1
                summaries
            }
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val before = viewModel.uiState.value
                val beforeObserveSinceCalls = observeSinceCalls
                assertFalse(before.isLoading)
                settingsRepo.emitAppTheme(AppTheme.LIGHT)
                advanceUntilIdle()
                val after = viewModel.uiState.value

                assertEquals(beforeObserveSinceCalls, observeSinceCalls)
                assertSame(before.chartSeries.hrv, after.chartSeries.hrv)
                assertSame(before.chartSeries.rhr, after.chartSeries.rhr)
                assertSame(before.chartSeries.spo2, after.chartSeries.spo2)
                assertSame(before.chartSeries.bodyTemp, after.chartSeries.bodyTemp)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `date change does not pair selected summary with stale DailyMetrics baseline`() =
        runTest {
            val today = LocalDate.now()
            val nextDate = today.minusDays(1)
            val nextDateSummary = MutableStateFlow<DailySummary?>(summary(date = nextDate, hrv = 80, rhr = 70))
            val nextDateMetrics = MutableSharedFlow<DailyMetrics?>()
            val nextDateMidnightMs =
                nextDate
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            every { dailySummaryRepository.observeByDate(match { it == nextDateMidnightMs }) } returns nextDateSummary
            customMetricsFlowsByDate[nextDate] = nextDateMetrics

            viewModel = createViewModel()
            val emittedStates = mutableListOf<VitalsUiState>()
            val collector = backgroundScope.launch { viewModel.uiState.collect { emittedStates += it } }
            try {
                advanceUntilIdle()
                assertEquals(today, viewModel.uiState.value.selectedDate)
                assertEquals(41, viewModel.uiState.value.presentation.hrv.baseline)

                selectedDateFlow.value = nextDate
                advanceUntilIdle()

                assertFalse(
                    "Selected-date content must never be emitted with the previous date's presentation",
                    emittedStates.any { state ->
                        state.selectedDate == nextDate &&
                            state.latestSummary?.date == nextDate &&
                            (
                                state.presentation.hrv.baseline == 41 ||
                                    state.presentation.hrv.value != state.latestSummary.nocturnalHrv
                            )
                    },
                )

                val waitingForMetrics = viewModel.uiState.value
                assertEquals(nextDate, waitingForMetrics.selectedDate)
                assertEquals(80, waitingForMetrics.presentation.hrv.value)
                assertNull(waitingForMetrics.presentation.hrv.baseline)
                assertEquals(MetricStatus.CALIBRATING, waitingForMetrics.presentation.hrv.status)

                nextDateMetrics.emit(dailyMetrics(date = nextDate, hrv = 80, rhr = 70, hrvBaselineRounded = 79))
                advanceUntilIdle()

                assertEquals(79, viewModel.uiState.value.presentation.hrv.baseline)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `date change does not pair selected summary with stale body temperature baseline`() =
        runTest {
            val today = LocalDate.now()
            val nextDate = today.minusDays(1)
            val initialDateBaseline = MutableStateFlow<Float?>(36.5f)
            val nextDateBaseline = MutableSharedFlow<Float?>()
            val nextDateSummary = MutableStateFlow<DailySummary?>(summary(date = nextDate, bodyTemp = 36.8f))
            val nextDateMidnightMs =
                nextDate
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            every { dailySummaryRepository.observeByDate(match { it == nextDateMidnightMs }) } returns nextDateSummary
            every { bodyTemperatureBaselineProvider.observeBaseline(any()) } answers {
                when (val date = firstArg<LocalDate>()) {
                    today -> initialDateBaseline
                    nextDate -> nextDateBaseline
                    else -> error("Unexpected baseline date $date")
                }
            }

            viewModel = createViewModel()
            val emittedStates = mutableListOf<VitalsUiState>()
            val collector = backgroundScope.launch { viewModel.uiState.collect { emittedStates += it } }
            try {
                advanceUntilIdle()
                assertEquals(36.5f, viewModel.uiState.value.presentation.baselineBodyTemp)

                selectedDateFlow.value = nextDate
                advanceUntilIdle()

                assertFalse(
                    "Selected-date content must never be emitted with the previous date's body temperature baseline",
                    emittedStates.any { state ->
                        state.selectedDate == nextDate &&
                            state.latestSummary?.date == nextDate &&
                            state.presentation.baselineBodyTemp == 36.5f
                    },
                )

                val waitingForBaseline = viewModel.uiState.value
                assertEquals(nextDate, waitingForBaseline.selectedDate)
                assertNull(waitingForBaseline.presentation.baselineBodyTemp)

                nextDateBaseline.emit(36.1f)
                advanceUntilIdle()

                assertEquals(36.1f, viewModel.uiState.value.presentation.baselineBodyTemp)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `historical selected date uses that dates DailyMetrics for assessment baselines`() =
        runTest {
            val today = LocalDate.now()
            val historicalDate = today.minusDays(3)
            selectedDateFlow.value = historicalDate
            summaries.value =
                listOf(
                    summary(date = today, hrv = 80, rhr = 70, spo2 = 96f),
                    summary(date = historicalDate, hrv = 42, rhr = 63, spo2 = 95f),
                )
            metricsFlow(today).value =
                dailyMetrics(
                    date = today,
                    hrv = 80,
                    rhr = 70,
                    hrvBaselineRounded = 80,
                    rhrBaselineRounded = 50,
                    rhrSnapshotRaw = 50f,
                )
            metricsFlow(historicalDate).value =
                dailyMetrics(
                    date = historicalDate,
                    hrv = 42,
                    rhr = 63,
                    hrvBaselineRounded = 41,
                    rhrBaselineRounded = 60,
                    rhrSnapshotRaw = 60f,
                )

            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val presentation = viewModel.uiState.value.presentation

                assertEquals(41, presentation.hrv.baseline)
                assertEquals(60, presentation.rhr.baseline)
                assertEquals(MetricStatus.NEUTRAL, presentation.hrv.status)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `temperature summaries reach the Vitals chart series`() =
        runTest {
            summaries.value =
                listOf(
                    summary(date = LocalDate.now(), bodyTemp = 36.7f),
                )
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                assertEquals(
                    36.7f,
                    viewModel.uiState.value.chartSeries.bodyTemp
                        .last()
                        .value,
                )
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `body temperature baseline refreshes presentation without changing selected date or chart series`() =
        runTest {
            bodyTemperatureBaseline.value = null
            settingsRepo.emitUnitSystem(UnitSystem.IMPERIAL)
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val before = viewModel.uiState.value
                val selectedDate = selectedDateFlow.value

                bodyTemperatureBaseline.value = 36.5f
                advanceUntilIdle()

                val after = viewModel.uiState.value
                assertEquals(
                    UnitConverter.celsiusToDisplayTemperature(36.5f, UnitSystem.IMPERIAL),
                    after.presentation.baselineBodyTemp,
                )
                assertEquals(selectedDate, selectedDateFlow.value)
                assertSame(before.chartSeries, after.chartSeries)
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `date navigation ignores emissions from the previous body temperature baseline stream`() =
        runTest {
            val initialDate = selectedDateFlow.value
            val nextDate = initialDate.minusDays(1)
            val initialDateBaseline = MutableStateFlow<Float?>(36.2f)
            val nextDateBaseline = MutableStateFlow<Float?>(36.4f)
            val baselineByDate =
                mapOf(
                    initialDate to initialDateBaseline,
                    nextDate to nextDateBaseline,
                )
            every { bodyTemperatureBaselineProvider.observeBaseline(any()) } answers {
                baselineByDate.getValue(firstArg<LocalDate>())
            }
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                assertEquals(36.2f, viewModel.uiState.value.presentation.baselineBodyTemp)

                selectedDateFlow.value = nextDate
                advanceUntilIdle()
                assertEquals(36.4f, viewModel.uiState.value.presentation.baselineBodyTemp)

                initialDateBaseline.value = 37.1f
                advanceUntilIdle()
                assertEquals(36.4f, viewModel.uiState.value.presentation.baselineBodyTemp)

                nextDateBaseline.value = 36.5f
                advanceUntilIdle()
                assertEquals(36.5f, viewModel.uiState.value.presentation.baselineBodyTemp)
            } finally {
                collector.cancel()
            }
        }

    private fun summary(
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

    private fun dailyMetrics(
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

    private fun metricsFlow(date: LocalDate): MutableStateFlow<DailyMetrics?> =
        metricsByDate.getOrPut(date) { MutableStateFlow(null) }

    private class FakeUserPreferencesReader : UserPreferencesReader {
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
