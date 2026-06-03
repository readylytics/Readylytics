package com.gregor.lauritz.healthdashboard.ui.weight

import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WeightRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeightDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: WeightDetailViewModel
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        weightRepository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getLatest() } returns null
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        selectedDateRepo = SelectedDateRepository()
    }

    private fun createViewModel(): WeightDetailViewModel =
        WeightDetailViewModel(
            weightRepository = weightRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepo,
        )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has null weightDisplay and bmiDisplay`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(null, state.weightDisplay)
            assertEquals(null, state.bmiDisplay)
        }

    @Test
    fun `weightDisplay formats metric weight correctly`() =
        runTest {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 75f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC))

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.weightDisplay != null }

            assertEquals("75.0", state.weightDisplay)
        }

    @Test
    fun `weightDisplay formats imperial weight correctly`() =
        runTest {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 75f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.IMPERIAL))

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.weightDisplay != null }

            // 75 kg * 2.20462 = 165.3465 lbs -> "165.3"
            assertEquals("165.3", state.weightDisplay)
        }

    @Test
    fun `bmiDisplay formats BMI correctly when height is set`() =
        runTest {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 70f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            // height 175 cm -> BMI = 70 / (1.75 * 1.75) = 22.857 -> "22.9"
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = 175f))

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.bmiDisplay != null }

            assertEquals("22.9", state.bmiDisplay)
        }

    @Test
    fun `bmiDisplay is null when height is not set`() =
        runTest {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 70f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = null))

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.weightDisplay != null }

            assertEquals(null, state.bmiDisplay)
        }
}
