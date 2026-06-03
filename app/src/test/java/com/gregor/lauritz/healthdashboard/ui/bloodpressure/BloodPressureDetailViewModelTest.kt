package com.gregor.lauritz.healthdashboard.ui.bloodpressure

import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.repository.BloodPressureRepository
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BloodPressureDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: BloodPressureDetailViewModel
    private lateinit var repository: BloodPressureRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getLatest() } returns null
            }
        val mockDao = mockk<com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao> {
            every { observeEarliestDateMs() } returns flowOf(null)
            every { observeAllDateMidnightMs() } returns flowOf(emptyList())
        }
        selectedDateRepo = SelectedDateRepository(
            dao = mockDao,
            appScope = CoroutineScope(testDispatcher)
        )
    }

    private fun createViewModel(): BloodPressureDetailViewModel =
        BloodPressureDetailViewModel(
            bloodPressureRepository = repository,
            selectedDateRepository = selectedDateRepo,
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
            assertNull(state.statusLabel)
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

    // --- systolicStatus boundaries ---

    @Test
    fun `systolicStatus is OPTIMAL for systolic below 120`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 119, diastolic = 70)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.OPTIMAL, state.systolicStatus)
        }

    @Test
    fun `systolicStatus is NEUTRAL for systolic 120 to 129`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 125, diastolic = 70)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.NEUTRAL, state.systolicStatus)
        }

    @Test
    fun `systolicStatus is WARNING for systolic 130 to 139`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 135, diastolic = 70)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.WARNING, state.systolicStatus)
        }

    @Test
    fun `systolicStatus is POOR for systolic 140 or above`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 140, diastolic = 70)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals(MetricStatus.POOR, state.systolicStatus)
        }

    // --- diastolicStatus boundaries ---

    @Test
    fun `diastolicStatus is OPTIMAL for diastolic below 80`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 110, diastolic = 79)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestDiastolic != null }

            assertEquals(MetricStatus.OPTIMAL, state.diastolicStatus)
        }

    @Test
    fun `diastolicStatus is WARNING for diastolic 80 to 89`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 110, diastolic = 85)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestDiastolic != null }

            assertEquals(MetricStatus.WARNING, state.diastolicStatus)
        }

    @Test
    fun `diastolicStatus is POOR for diastolic 90 or above`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 110, diastolic = 90)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestDiastolic != null }

            assertEquals(MetricStatus.POOR, state.diastolicStatus)
        }

    // --- statusLabel ---

    @Test
    fun `statusLabel is Normal when systolic below 120 and diastolic below 80`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 115, diastolic = 75)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals("Normal", state.statusLabel)
        }

    @Test
    fun `statusLabel is Elevated when systolic 120 to 129 and diastolic below 80`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 125, diastolic = 75)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals("Elevated", state.statusLabel)
        }

    @Test
    fun `statusLabel is High when systolic 130 or above`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 135, diastolic = 75)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals("High", state.statusLabel)
        }

    @Test
    fun `statusLabel is High when diastolic 80 or above`() =
        runTest {
            coEvery { repository.getLatest() } returns bloodPressureEntity(systolic = 115, diastolic = 80)

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic != null }

            assertEquals("High", state.statusLabel)
        }

    @Test
    fun `statusLabel is null when no latest record`() =
        runTest {
            coEvery { repository.getLatest() } returns null

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.latestSystolic == null }

            assertNull(state.statusLabel)
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

    // --- helpers ---

    private fun bloodPressureEntity(
        systolic: Int,
        diastolic: Int,
    ): BloodPressureRecordEntity =
        BloodPressureRecordEntity(
            id = "test-id",
            timestampMs = System.currentTimeMillis(),
            systolicMmHg = systolic,
            diastolicMmHg = diastolic,
        )
}
