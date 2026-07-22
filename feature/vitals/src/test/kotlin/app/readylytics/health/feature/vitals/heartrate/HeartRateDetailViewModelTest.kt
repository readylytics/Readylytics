package app.readylytics.health.feature.vitals.heartrate

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: HeartRateDetailViewModel
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var selectedDateRepo: SelectedDateStore

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        heartRateRepository =
            mockk {
                every { observeByTimeRange(any(), any()) } returns MutableStateFlow(emptyList())
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        selectedDateRepo =
            mockk {
                every { selectedDate } returns selectedDateFlow
                every { earliestDate } returns earliestDateFlow
                coEvery { updateSelectedDate(any()) } answers {
                    selectedDateFlow.value = firstArg<LocalDate>()
                }
            }
    }

    private fun createViewModel(): HeartRateDetailViewModel =
        HeartRateDetailViewModel(
            heartRateRepository = heartRateRepository,
            settingsRepository = settingsRepo,
            selectedDateRepository = selectedDateRepo,
            clock = java.time.Clock.systemDefaultZone(),
            defaultDispatcher = testDispatcher,
        )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty zone totals`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(emptyMap<Int, ZoneTotal>(), state.zoneTotals)
        }

    @Test
    fun `zone totals are empty when fewer than two samples`() =
        runTest {
            val singleSample =
                listOf(
                    HeartRateRecordData(
                        id = "1",
                        timestampMs = 0L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                )
            every { heartRateRepository.observeByTimeRange(any(), any()) } returns
                MutableStateFlow(singleSample)

            viewModel = createViewModel()

            val state = viewModel.uiState.first { !it.isLoading }
            assertEquals(emptyMap<Int, ZoneTotal>(), state.zoneTotals)
        }

    @Test
    fun `zone totals are calculated correctly for two samples in same zone`() =
        runTest {
            // Arrange: two samples 60 seconds apart both in zone 1 (100 bpm)
            val prefs = UserPreferences()
            every { settingsRepo.userPreferences } returns MutableStateFlow(prefs)
            val samples =
                listOf(
                    HeartRateRecordData(
                        id = "1",
                        timestampMs = 0L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                    HeartRateRecordData(
                        id = "2",
                        timestampMs = 60_000L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                )
            every { heartRateRepository.observeByTimeRange(any(), any()) } returns
                MutableStateFlow(samples)

            viewModel = createViewModel()

            // Act
            val state = viewModel.uiState.first { !it.isLoading }

            // Assert: only one zone entry, 100% of time in that zone
            assertEquals(1, state.zoneTotals.size)
            val zone = state.zoneTotals.values.first()
            assertEquals(60_000L, zone.durationMs)
            assertEquals(1.0f, zone.percent, 0.001f)
            assertEquals("100%", zone.formattedPercent)
        }

    @Test
    fun `zone totals formattedPercent uses MetricFormatter for each zone`() =
        runTest {
            // Arrange: samples split 50/50 across two zones
            // zone 0 = 60 bpm (below zone1MinBpm=95 default)
            // zone 1 = 100 bpm (within zone1MinBpm=95..zone1MaxBpm=114 default)
            val prefs = UserPreferences()
            every { settingsRepo.userPreferences } returns MutableStateFlow(prefs)
            val samples =
                listOf(
                    HeartRateRecordData(
                        id = "1",
                        timestampMs = 0L,
                        beatsPerMinute = 60,
                        recordType = "instant",
                    ),
                    HeartRateRecordData(
                        id = "2",
                        timestampMs = 60_000L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                    HeartRateRecordData(
                        id = "3",
                        timestampMs = 120_000L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                )
            every { heartRateRepository.observeByTimeRange(any(), any()) } returns
                MutableStateFlow(samples)

            viewModel = createViewModel()

            // Act
            val state = viewModel.uiState.first { !it.isLoading }

            // Assert: two zones each at 50%, formattedPercent matches MetricFormatter output
            assertEquals(2, state.zoneTotals.size)
            state.zoneTotals.values.forEach { zoneTotal ->
                assertEquals(0.5f, zoneTotal.percent, 0.001f)
                assertEquals("50%", zoneTotal.formattedPercent)
            }
        }

    @Test
    fun `zone totals exclude segments longer than 10 minutes`() =
        runTest {
            // Arrange: one valid 60s segment, then a gap > 10 min (excluded)
            val prefs = UserPreferences()
            every { settingsRepo.userPreferences } returns MutableStateFlow(prefs)
            val tenMinMs = 10 * 60 * 1000L
            val samples =
                listOf(
                    HeartRateRecordData(
                        id = "1",
                        timestampMs = 0L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                    HeartRateRecordData(
                        id = "2",
                        timestampMs = 60_000L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                    HeartRateRecordData(
                        id = "3",
                        timestampMs = 60_000L + tenMinMs + 1L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                )
            every { heartRateRepository.observeByTimeRange(any(), any()) } returns
                MutableStateFlow(samples)

            viewModel = createViewModel()

            // Act
            val state = viewModel.uiState.first { !it.isLoading }

            // Assert: only the valid 60s segment counted; gap > 10 min excluded
            assertEquals(1, state.zoneTotals.size)
            val zone = state.zoneTotals.values.first()
            assertEquals(60_000L, zone.durationMs)
            assertEquals("100%", zone.formattedPercent)
        }

    @Test
    fun `rapid successive invalidations collapse into a single re-render via debounce`() =
        runTest {
            // PERF-005/WP-23: simulates a resync's 5,000-row ingest batches invalidating
            // observeByTimeRange in quick succession -- the debounce(500) added to the view model
            // must collapse them into one downstream re-render instead of one per batch.
            val burst = MutableSharedFlow<List<HeartRateRecordData>>(replay = 1)
            burst.tryEmit(emptyList())
            every { heartRateRepository.observeByTimeRange(any(), any()) } returns burst

            viewModel = createViewModel()

            val collected = mutableListOf<HeartRateDetailUiState>()
            val job = launch { viewModel.uiState.collect { collected += it } }
            advanceTimeBy(600)
            val countAfterInitialSettle = collected.size

            repeat(5) { i ->
                burst.emit(
                    listOf(
                        HeartRateRecordData(
                            id = "batch$i",
                            timestampMs = i * 1_000L,
                            beatsPerMinute = 100,
                            recordType = "instant",
                        ),
                    ),
                )
                advanceTimeBy(100) // well within the 500ms debounce window
            }
            advanceTimeBy(600) // let the debounce window elapse with no further emissions
            advanceUntilIdle()
            job.cancel()

            assertEquals(countAfterInitialSettle + 1, collected.size)
            assertEquals(1, collected.last().samples.size)
            // The collapsed emission reflects the last (5th) batch, not an intermediate one.
            assertEquals(
                4_000L,
                collected
                    .last()
                    .samples
                    .single()
                    .timeMs,
            )
        }

    @Test
    fun `selected date defaults to today`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(LocalDate.now(), state.selectedDate)
        }
}
