package app.readylytics.health.ui.bodyfat

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.core.ui.common.TimeRange
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class BodyFatDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: BodyFatDetailViewModel
    private lateinit var bodyFatRepository: BodyFatRepository
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepo: SelectedDateRepository

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        bodyFatRepository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getLatest() } returns null
                coEvery { getPrevious(any()) } returns null
            }
        weightRepository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
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

    private fun createViewModel(): BodyFatDetailViewModel =
        BodyFatDetailViewModel(
            bodyFatRepository = bodyFatRepository,
            weightRepository = weightRepository,
            settingsRepo = settingsRepo,
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

    @Test
    fun `initial state has null bodyFatDisplay`() =
        runTest {
            viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertNull(state.bodyFatDisplay)
        }

    @Test
    fun `bodyFatDisplay formats value with one decimal and percent sign`() =
        runTest {
            val record =
                BodyFatRecordEntity(
                    id = "1",
                    timestampMs = System.currentTimeMillis(),
                    bodyFatPercent = 18.5f,
                )
            coEvery { bodyFatRepository.getLatest() } returns record

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.bodyFatDisplay != null }
            assertEquals("18.5", state.bodyFatDisplay)
        }

    @Test
    fun `bodyFatDisplay is null when no latest record`() =
        runTest {
            coEvery { bodyFatRepository.getLatest() } returns null

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.latestBodyFat == null }
            assertNull(state.bodyFatDisplay)
        }

    @Test
    fun `optimalRangeDisplay formats male age 30 correctly`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(age = 30, gender = Gender.MALE),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.optimalRangeMax > 0f }
            assertEquals("0–19.0%", state.optimalRangeDisplay)
        }

    @Test
    fun `optimalRangeDisplay formats female age 50 correctly`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(age = 50, gender = Gender.FEMALE),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.optimalRangeMax > 0f }
            assertEquals("0–34.0%", state.optimalRangeDisplay)
        }

    // --- historyItems ---

    @Test
    fun `historyItems include lean mass when same-day weight record exists`() =
        runTest {
            val now = System.currentTimeMillis()
            val bodyFatRecord = BodyFatRecordEntity(id = "1", timestampMs = now, bodyFatPercent = 14.2f)
            val weightRecord = WeightRecordEntity(id = "1", timestampMs = now, weightKg = 78.4f)
            coEvery { bodyFatRepository.getByDateRange(any(), any()) } returns listOf(bodyFatRecord)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(weightRecord)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(age = 30, gender = Gender.MALE))

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            val item = state.historyItems[0]
            assertEquals(14.2f, item.bodyFatPercent, 0.01f)
            // 78.4 * (1 - 14.2/100) = 67.2752
            assertEquals(67.2752f, item.leanMassDisplay!!, 0.01f)
            assertEquals(MetricStatus.OPTIMAL, item.status)
        }

    @Test
    fun `historyItems leanMass is null when no same-day weight record`() =
        runTest {
            val bodyFatRecord =
                BodyFatRecordEntity(
                    id = "1",
                    timestampMs = System.currentTimeMillis(),
                    bodyFatPercent = 18f,
                )
            coEvery { bodyFatRepository.getByDateRange(any(), any()) } returns listOf(bodyFatRecord)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns emptyList()

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(age = 30, gender = Gender.MALE))

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            assertNull(state.historyItems[0].leanMassDisplay)
        }

    @Test
    fun `historyItems lean mass converts to imperial units`() =
        runTest {
            val now = System.currentTimeMillis()
            val bodyFatRecord = BodyFatRecordEntity(id = "1", timestampMs = now, bodyFatPercent = 14.2f)
            val weightRecord = WeightRecordEntity(id = "1", timestampMs = now, weightKg = 78.4f)
            coEvery { bodyFatRepository.getByDateRange(any(), any()) } returns listOf(bodyFatRecord)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(weightRecord)

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(age = 30, gender = Gender.MALE, unitSystem = UnitSystem.IMPERIAL))

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            val item = state.historyItems[0]
            // 67.2752 kg * 2.20462 = 148.3 lbs
            assertEquals(148.3f, item.leanMassDisplay!!, 0.1f)
            assertEquals(UnitSystem.IMPERIAL, item.unitSystem)
        }

    @Test
    fun `onRangeSelected updates selectedRange`() =
        runTest {
            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
        }
}
