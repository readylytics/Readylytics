package app.readylytics.health.feature.vitals.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.scoring.HrvBaselineProvider
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.domain.sync.ForegroundSyncGateway
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
    private val settingsRepo = FakeUserPreferencesReader()

    private lateinit var viewModel: VitalsViewModel

    private val dailySummaryRepository =
        mockk<DailySummaryRepository> {
            every { observeSince(any()) } returns summaries
            every { observeByDate(any()) } returns MutableStateFlow(null)
        }
    private val dailyMetricsRepository = mockk<DailyMetricsRepository>(relaxed = true)
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
    private val hrvBaselineProvider =
        mockk<HrvBaselineProvider> {
            coEvery { getRoundedHrvBaseline(any()) } returns 50
        }
    private val rhrBaselineProvider =
        mockk<RhrBaselineProvider> {
            coEvery { getRoundedRhrBaseline(any()) } returns 48
        }
    private val bodyTemperatureBaselineProvider =
        mockk<BodyTemperatureBaselineProvider> {
            coEvery { getBaseline(any()) } returns 36.5f
        }

    private fun createViewModel() =
        VitalsViewModel(
            dailySummaryRepository = dailySummaryRepository,
            dailyMetricsRepository = dailyMetricsRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateStore,
            foregroundSyncController = foregroundSyncGateway,
            savedStateHandle = SavedStateHandle(),
            hrvBaselineProvider = hrvBaselineProvider,
            rhrBaselineProvider = rhrBaselineProvider,
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
        syncing.value = false
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
    fun `preference emission updates presentation without rebuilding chart series`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val before = viewModel.uiState.value
                assertFalse(before.isLoading)
                settingsRepo.emitHrvThresholds(optimal = 0.95f, warning = 0.85f)
                advanceUntilIdle()
                val after = viewModel.uiState.value

                assertSame(before.chartSeries.hrv, after.chartSeries.hrv)
                assertSame(before.chartSeries.rhr, after.chartSeries.rhr)
                assertSame(before.chartSeries.spo2, after.chartSeries.spo2)
                assertSame(before.chartSeries.bodyTemp, after.chartSeries.bodyTemp)
            } finally {
                collector.cancel()
            }
        }

    private fun summary(
        date: LocalDate,
        hrv: Int? = null,
        rhr: Int? = null,
        spo2: Float? = null,
    ): DailySummary =
        DailySummary(
            date = date,
            nocturnalHrv = hrv,
            restingHeartRate = rhr,
            avgSleepingSpo2 = spo2,
            isCalibrating = false,
        )

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
    }
}
