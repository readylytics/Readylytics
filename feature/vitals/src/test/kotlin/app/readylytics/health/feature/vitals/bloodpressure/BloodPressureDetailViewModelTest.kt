package app.readylytics.health.feature.vitals.bloodpressure

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.BloodPressureRecord
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.repository.BloodPressureRepository
import app.readylytics.health.feature.vitals.R
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

private fun BloodPressureRecordEntity(
    id: String,
    timestampMs: Long,
    systolicMmHg: Int,
    diastolicMmHg: Int,
    deviceName: String? = null,
): BloodPressureRecord =
    BloodPressureRecord(id, Instant.ofEpochMilli(timestampMs), systolicMmHg, diastolicMmHg, deviceName)

@OptIn(ExperimentalCoroutinesApi::class)
class BloodPressureDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: BloodPressureDetailViewModel
    private lateinit var repository: BloodPressureRepository
    private lateinit var selectedDateRepo: SelectedDateStore

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getLatest() } returns null
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

    private fun createViewModel(): BloodPressureDetailViewModel =
        BloodPressureDetailViewModel(
            bloodPressureRepository = repository,
            selectedDateRepository = selectedDateRepo,
            ioDispatcher = testDispatcher,
        )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    // --- initial state ---

    @Test
    fun `initial state has calibrating statuses and null display`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(TimeRange.SEVEN_DAYS, state.selectedRange)
            assertEquals(MetricStatus.CALIBRATING, state.systolicStatus)
            assertEquals(MetricStatus.CALIBRATING, state.diastolicStatus)
            assertNull(state.bloodPressureDisplay)
            assertNull(state.bloodPressureStatus)
        }

    // --- bloodPressureDisplay ---

    @Test
    fun `bloodPressureDisplay formats known systolic and diastolic`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 120, diastolic = 80)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.bloodPressureDisplay != null }

            assertEquals("120/80", state.bloodPressureDisplay)
        }

    @Test
    fun `bloodPressureDisplay is null when no latest record`() =
        runTest {
            coEvery { repository.getLatest() } returns null

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic == null }

            assertNull(state.bloodPressureDisplay)
        }

    // --- canonical blood-pressure ladder ---

    @Test
    fun `latest 120 over 80 is Normal with Optimal component gauges`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 120, diastolic = 80)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.OPTIMAL, state.systolicStatus)
            assertEquals(MetricStatus.OPTIMAL, state.diastolicStatus)
            assertEquals(BloodPressureStatus.Optimal, state.bloodPressureStatus)
        }

    @Test
    fun `latest 120 over 81 is Elevated with Neutral diastolic gauge`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 120, diastolic = 81)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.NEUTRAL, state.diastolicStatus)
            assertEquals(BloodPressureStatus.Neutral, state.bloodPressureStatus)
        }

    @Test
    fun `latest 129 over 89 is Elevated`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 129, diastolic = 89)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.NEUTRAL, state.systolicStatus)
            assertEquals(MetricStatus.NEUTRAL, state.diastolicStatus)
            assertEquals(BloodPressureStatus.Neutral, state.bloodPressureStatus)
        }

    @Test
    fun `latest 130 over 90 is High with Warning diastolic gauge`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 130, diastolic = 90)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.WARNING, state.systolicStatus)
            assertEquals(MetricStatus.WARNING, state.diastolicStatus)
            assertEquals(BloodPressureStatus.HypertensionStage1, state.bloodPressureStatus)
        }

    @Test
    fun `bloodPressureStatus is null when no latest record`() =
        runTest {
            coEvery { repository.getLatest() } returns null

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic == null }

            assertNull(state.bloodPressureStatus)
        }

    @Test
    fun `current blood pressure status maps to its rendered label resource`() {
        assertEquals(R.string.bp_status_normal, bloodPressureStatusLabelRes(BloodPressureStatus.Optimal))
        assertEquals(R.string.bp_status_elevated, bloodPressureStatusLabelRes(BloodPressureStatus.Neutral))
        assertEquals(R.string.bp_status_stage1, bloodPressureStatusLabelRes(BloodPressureStatus.HypertensionStage1))
        assertEquals(R.string.bp_status_stage2, bloodPressureStatusLabelRes(BloodPressureStatus.HypertensionStage2))
    }

    // --- historyItems ---

    @Test
    fun `historyItems is empty when no records`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(emptyList<Any>(), state.historyItems)
        }

    @Test
    fun `historyItems are sorted newest first with correct status mapping`() =
        runTest {
            val older = bloodPressureEntity(systolic = 140, diastolic = 70, timestampMs = 1_000L)
            val newer = bloodPressureEntity(systolic = 180, diastolic = 110, timestampMs = 2_000L)
            coEvery { repository.getByDateRange(any(), any()) } returns listOf(older, newer)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            assertEquals(2, state.historyItems.size)
            assertEquals(2_000L, state.historyItems[0].timestampMs)
            assertEquals(BloodPressureStatus.HypertensionStage2, state.historyItems[0].status)
            assertEquals(1_000L, state.historyItems[1].timestampMs)
            assertEquals(BloodPressureStatus.HypertensionStage2, state.historyItems[1].status)
        }

    // --- onRangeSelected ---

    @Test
    fun `onRangeSelected updates selectedRange`() =
        runTest {
            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
        }

    @Test
    fun `twelve month range buckets systolic and diastolic into eight week points`() =
        runTest {
            val start = LocalDate.of(2026, 1, 1)
            selectedDateFlow.value = start
            val zone = java.time.ZoneId.systemDefault()
            val records =
                listOf(
                    // Octad 1 (weeks 1-8): one record
                    bloodPressureEntity(
                        systolic = 118,
                        diastolic = 78,
                        timestampMs =
                            start
                                .plusMonths(1)
                                .atStartOfDay(zone)
                                .toInstant()
                                .toEpochMilli(),
                    ),
                    // Octad 2 (weeks 9-16): one record
                    bloodPressureEntity(
                        systolic = 122,
                        diastolic = 82,
                        timestampMs =
                            start
                                .plusMonths(2)
                                .atStartOfDay(zone)
                                .toInstant()
                                .toEpochMilli(),
                    ),
                    // Octad 3 (weeks 17-24): one record
                    bloodPressureEntity(
                        systolic = 120,
                        diastolic = 80,
                        timestampMs =
                            start
                                .plusMonths(4)
                                .atStartOfDay(zone)
                                .toInstant()
                                .toEpochMilli(),
                    ),
                )
            coEvery { repository.getByDateRange(any(), any()) } returns records
            coEvery { repository.getLatest() } returns records.last()

            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.TWELVE_MONTHS)

            val state =
                viewModel.uiState.first {
                    it.selectedRange == TimeRange.TWELVE_MONTHS && !it.isLoading
                }

            // Three populated octads, one record each.
            assertEquals(3, state.dailySystolic.count { it.value != null })
            assertEquals(3, state.dailyDiastolic.count { it.value != null })
            assertEquals(listOf(118f, 122f, 120f), state.dailySystolic.filter { it.value != null }.map { it.value })
            assertEquals(listOf(78f, 82f, 80f), state.dailyDiastolic.filter { it.value != null }.map { it.value })
            assertEquals(120, state.systolicPeriodSummary?.average?.roundToInt())
            assertEquals(80, state.diastolicPeriodSummary?.average?.roundToInt())
        }

    @Test
    fun `daily granularity range has null period summaries`() =
        runTest {
            viewModel = createViewModel()
            val state =
                viewModel.uiState.first {
                    it.selectedRange == TimeRange.SEVEN_DAYS && !it.isLoading
                }
            assertNull(state.systolicPeriodSummary)
            assertNull(state.diastolicPeriodSummary)
        }

    // --- helpers ---

    private fun bloodPressureEntity(
        systolic: Int,
        diastolic: Int,
        timestampMs: Long = System.currentTimeMillis(),
    ): BloodPressureRecord =
        BloodPressureRecordEntity(
            id = "test-id-$timestampMs",
            timestampMs = timestampMs,
            systolicMmHg = systolic,
            diastolicMmHg = diastolic,
        )
}
