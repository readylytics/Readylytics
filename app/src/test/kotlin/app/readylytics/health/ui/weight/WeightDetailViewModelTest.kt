package app.readylytics.health.ui.weight

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.repository.WeightRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class WeightDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: WeightDetailViewModel
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

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
        selectedDateRepo =
            mockk {
                every { selectedDate } returns selectedDateFlow
                every { earliestDate } returns earliestDateFlow
                coEvery { updateSelectedDate(any()) } answers {
                    selectedDateFlow.value = firstArg()
                }
            }
    }

    private fun createViewModel(): WeightDetailViewModel =
        WeightDetailViewModel(
            weightRepository = weightRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepo,
        )

    private suspend fun <T> collectWithCleanup(block: suspend () -> T): T =
        try {
            block()
        } finally {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.cancel()
            }
        }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has null weightDisplay and bmiDisplay`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertEquals(null, state.weightDisplay)
            assertEquals(null, state.bmiDisplay)
        }

    @Test
    fun `weightDisplay formats metric weight correctly`() =
        runTest(testDispatcher) {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 75f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC))

            viewModel = createViewModel()

            collectWithCleanup {
                val state = viewModel.uiState.first { it.weightDisplay != null }
                assertEquals("75.0", state.weightDisplay)
            }
        }

    @Test
    fun `weightDisplay formats imperial weight correctly`() =
        runTest(testDispatcher) {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 75f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.IMPERIAL))

            viewModel = createViewModel()

            collectWithCleanup {
                val state = viewModel.uiState.first { it.weightDisplay != null }

                // 75 kg * 2.20462 = 165.3465 lbs -> "165.3"
                assertEquals("165.3", state.weightDisplay)
            }
        }

    @Test
    fun `bmiDisplay formats BMI correctly when height is set`() =
        runTest(testDispatcher) {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 70f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            // height 175 cm -> BMI = 70 / (1.75 * 1.75) = 22.857 -> "22.9"
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = 175f))

            viewModel = createViewModel()

            collectWithCleanup {
                val state = viewModel.uiState.first { it.bmiDisplay != null }

                assertEquals("22.9", state.bmiDisplay)
            }
        }

    @Test
    fun `bmiDisplay is null when height is not set`() =
        runTest(testDispatcher) {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 70f)
            coEvery { weightRepository.getLatest() } returns record
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = null))

            viewModel = createViewModel()

            collectWithCleanup {
                val state = viewModel.uiState.first { it.weightDisplay != null }
                assertEquals(null, state.bmiDisplay)
            }
        }

    // --- historyItems ---

    @Test
    fun `historyItems are sorted newest first with delta and bmiStatus`() =
        runTest(testDispatcher) {
            val older = WeightRecordEntity(id = "1", timestampMs = 1_000L, weightKg = 80f)
            val newer = WeightRecordEntity(id = "2", timestampMs = 2_000L, weightKg = 79.6f)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(older, newer)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = 175f))

            viewModel = createViewModel()
            collectWithCleanup {
                val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

                assertEquals(2, state.historyItems.size)

                val newest = state.historyItems[0]
                assertEquals(2_000L, newest.timestampMs)
                assertEquals(79.6f, newest.weightDisplay, 0.01f)
                assertEquals(-0.4f, newest.deltaDisplay!!, 0.01f)
                assertEquals(BmiStatus.Neutral, newest.bmiStatus)

                val oldest = state.historyItems[1]
                assertEquals(1_000L, oldest.timestampMs)
                assertEquals(80f, oldest.weightDisplay, 0.01f)
                assertEquals(null, oldest.deltaDisplay)
            }
        }

    @Test
    fun `historyItems convert weight and delta to imperial units`() =
        runTest(testDispatcher) {
            val older = WeightRecordEntity(id = "1", timestampMs = 1_000L, weightKg = 80f)
            val newer = WeightRecordEntity(id = "2", timestampMs = 2_000L, weightKg = 79f)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(older, newer)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.IMPERIAL, heightCm = 175f))

            viewModel = createViewModel()
            collectWithCleanup {
                val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

                val newest = state.historyItems[0]
                // -1 kg * 2.20462 = -2.20462 lbs
                assertEquals(-2.20462f, newest.deltaDisplay!!, 0.01f)
            }
        }

    @Test
    fun `historyItems bmiStatus is null when height is not set`() =
        runTest(testDispatcher) {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 70f)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = null))

            viewModel = createViewModel()
            collectWithCleanup {
                val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

                assertEquals(null, state.historyItems[0].bmiStatus)
            }
        }
}
