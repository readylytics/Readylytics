package app.readylytics.health.feature.vitals.heartrate

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.model.domain.repository.HeartRateSeries
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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
                every { observeTimelineWithResolution(any(), any()) } returns
                    MutableStateFlow(HeartRateSeries(points = emptyList(), resolution = HeartRateResolution.RAW))
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
    fun `empty day exposes calibrating average status`() =
        runTest {
            viewModel = createViewModel()

            val state = viewModel.uiState.first { !it.isLoading }

            assertEquals(MetricStatus.CALIBRATING, state.averageStatus)
        }

    @Test
    fun `populated day exposes neutral average status`() =
        runTest {
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns
                MutableStateFlow(
                    HeartRateSeries(
                        points =
                            listOf(
                                HeartRateRecordData(
                                    id = "1",
                                    timestampMs = 0L,
                                    beatsPerMinute = 100,
                                    recordType = "instant",
                                ),
                            ),
                        resolution = HeartRateResolution.RAW,
                    ),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { !it.isLoading }

            assertEquals(MetricStatus.NEUTRAL, state.averageStatus)
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
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns
                MutableStateFlow(HeartRateSeries(points = singleSample, resolution = HeartRateResolution.RAW))

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
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns
                MutableStateFlow(HeartRateSeries(points = samples, resolution = HeartRateResolution.RAW))

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
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns
                MutableStateFlow(HeartRateSeries(points = samples, resolution = HeartRateResolution.RAW))

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
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns
                MutableStateFlow(HeartRateSeries(points = samples, resolution = HeartRateResolution.RAW))

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
    fun `sustained invalidations continue updating state before the source becomes quiet`() =
        runTest {
            val updates = MutableSharedFlow<HeartRateSeries>(replay = 1)
            updates.tryEmit(HeartRateSeries(points = emptyList(), resolution = HeartRateResolution.RAW))
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns updates
            viewModel = createViewModel()

            val collected = mutableListOf<HeartRateDetailUiState>()
            val job = launch { viewModel.uiState.collect { collected += it } }
            runCurrent()

            repeat(15) { index ->
                updates.emit(
                    HeartRateSeries(
                        points =
                            listOf(
                                HeartRateRecordData(
                                    id = "update$index",
                                    timestampMs = index * 1_000L,
                                    beatsPerMinute = 100,
                                    recordType = "instant",
                                ),
                            ),
                        resolution = HeartRateResolution.RAW,
                    ),
                )
                advanceTimeBy(100)
                runCurrent()
            }

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(collected.count { it.samples.isNotEmpty() } >= 2)
            job.cancel()
        }

    @Test
    fun `rapid successive invalidations render only the latest sampled value`() =
        runTest {
            // PERF-005/WP-23: simulates a resync's 5,000-row ingest batches invalidating
            // observeByTimeRange in quick succession -- the 500 ms sampling cadence must render
            // only the latest value in the period instead of one downstream state per batch.
            val burst = MutableSharedFlow<HeartRateSeries>(replay = 1)
            burst.tryEmit(HeartRateSeries(points = emptyList(), resolution = HeartRateResolution.RAW))
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns burst

            viewModel = createViewModel()

            val collected = mutableListOf<HeartRateDetailUiState>()
            val job = launch { viewModel.uiState.collect { collected += it } }
            advanceTimeBy(600)
            val countAfterInitialSettle = collected.size

            repeat(4) { i ->
                burst.emit(
                    HeartRateSeries(
                        points =
                            listOf(
                                HeartRateRecordData(
                                    id = "batch$i",
                                    timestampMs = i * 1_000L,
                                    beatsPerMinute = 100,
                                    recordType = "instant",
                                ),
                            ),
                        resolution = HeartRateResolution.RAW,
                    ),
                )
                advanceTimeBy(100) // all four updates stay within one 500 ms sampling period
            }
            advanceTimeBy(200) // let the sampling period emit its latest value
            job.cancel()
            advanceUntilIdle()

            assertEquals(countAfterInitialSettle + 1, collected.size)
            assertEquals(1, collected.last().samples.size)
            // The sampled emission reflects the fourth batch, not an intermediate one.
            assertEquals(
                3_000L,
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

    @Test
    fun `uiState reflects RECONSTRUCTED resolution when the day's samples come from the warm tier`() =
        runTest {
            every { heartRateRepository.observeTimelineWithResolution(any(), any()) } returns
                MutableStateFlow(
                    HeartRateSeries(
                        points =
                            listOf(
                                HeartRateRecordData(
                                    id = "warm:1000",
                                    timestampMs = 1000L,
                                    beatsPerMinute = 65,
                                    recordType = "RECONSTRUCTED",
                                    sessionId = null,
                                    deviceName = null,
                                ),
                            ),
                        resolution = HeartRateResolution.RECONSTRUCTED,
                    ),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { !it.isLoading }

            assertEquals(HeartRateResolution.RECONSTRUCTED, state.resolution)
        }

    @Test
    fun `uiState queries timeline using scoringZone instead of device zone`() =
        runTest {
            val scoringZone = ZoneId.of("Pacific/Honolulu")
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(scoringZoneId = scoringZone.id))

            val date = LocalDate.of(2026, 6, 10)
            selectedDateFlow.value = date

            viewModel = createViewModel()
            viewModel.uiState.first { !it.isLoading }

            val expectedStartMs = date.atStartOfDay(scoringZone).toInstant().toEpochMilli()
            val expectedEndMs =
                date
                    .plusDays(1)
                    .atStartOfDay(scoringZone)
                    .toInstant()
                    .toEpochMilli()

            verify { heartRateRepository.observeTimelineWithResolution(expectedStartMs, expectedEndMs) }
        }
}
