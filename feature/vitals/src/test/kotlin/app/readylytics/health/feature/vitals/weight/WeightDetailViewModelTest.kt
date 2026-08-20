package app.readylytics.health.feature.vitals.weight

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.domain.model.BmiCategory
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.WeightRecord
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.WeightRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private fun WeightRecordEntity(
    id: String,
    timestampMs: Long,
    weightKg: Float,
    deviceName: String? = null,
): WeightRecord = WeightRecord(id, Instant.ofEpochMilli(timestampMs), weightKg, deviceName)

@OptIn(ExperimentalCoroutinesApi::class)
class WeightDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: WeightDetailViewModel
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var selectedDateRepo: SelectedDateStore

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        weightRepository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getByDateRangePaged(any(), any(), any(), any()) } returns emptyList()
                coEvery { countByDateRange(any(), any()) } returns 0
                coEvery { getLatest() } returns null
                coEvery { getPrevious(any()) } returns null
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

    private fun createViewModel(): WeightDetailViewModel =
        WeightDetailViewModel(
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

    // --- historyItems ---

    @Test
    fun `historyItems are sorted newest first with delta and bmiStatus`() =
        runTest {
            val older = WeightRecordEntity(id = "1", timestampMs = 1_000L, weightKg = 80f)
            val newer = WeightRecordEntity(id = "2", timestampMs = 2_000L, weightKg = 79.6f)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(older, newer)
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(newer, older)
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 2

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = 175f))

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            assertEquals(2, state.historyItems.size)

            val newest = state.historyItems[0]
            assertEquals(2_000L, newest.timestampMs)
            assertEquals(79.6f, newest.weightDisplay, 0.01f)
            assertEquals(-0.4f, newest.deltaDisplay!!, 0.01f)
            assertEquals(BmiStatus.Warning, newest.bmiStatus)
            assertEquals(BmiCategory.OVERWEIGHT, newest.bmiCategory)

            val oldest = state.historyItems[1]
            assertEquals(1_000L, oldest.timestampMs)
            assertEquals(80f, oldest.weightDisplay, 0.01f)
            assertEquals(null, oldest.deltaDisplay)
        }

    @Test
    fun `historyItems convert weight and delta to imperial units`() =
        runTest {
            val older = WeightRecordEntity(id = "1", timestampMs = 1_000L, weightKg = 80f)
            val newer = WeightRecordEntity(id = "2", timestampMs = 2_000L, weightKg = 79f)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(older, newer)
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(newer, older)
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 2

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.IMPERIAL, heightCm = 175f))

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            val newest = state.historyItems[0]
            // -1 kg * 2.20462 = -2.20462 lbs
            assertEquals(-2.20462f, newest.deltaDisplay!!, 0.01f)
        }

    @Test
    fun `historyItems bmiStatus is null when height is not set`() =
        runTest {
            val record = WeightRecordEntity(id = "1", timestampMs = System.currentTimeMillis(), weightKg = 70f)
            coEvery { weightRepository.getByDateRange(any(), any()) } returns listOf(record)
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(record)
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 1

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = null))

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            assertEquals(null, state.historyItems[0].bmiStatus)
            assertEquals(null, state.historyItems[0].bmiCategory)
        }

    @Test
    fun `historyItems use canonical BMI status boundaries`() =
        runTest {
            val records =
                listOf(
                    WeightRecordEntity("underweight", 1_000L, 18.4f),
                    WeightRecordEntity("healthy", 2_000L, 18.5f),
                    WeightRecordEntity("overweight", 3_000L, 25f),
                    WeightRecordEntity("obesity", 4_000L, 30f),
                )
            coEvery { weightRepository.getByDateRange(any(), any()) } returns records
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns records
            coEvery { weightRepository.countByDateRange(any(), any()) } returns records.size
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC, heightCm = 100f))
            viewModel = createViewModel()

            val statuses =
                viewModel.uiState
                    .first { it.historyItems.size == records.size }
                    .historyItems
                    .associate { it.weightDisplay to it.bmiStatus }

            assertEquals(BmiStatus.Warning, statuses[18.4f])
            assertEquals(BmiStatus.Optimal, statuses[18.5f])
            assertEquals(BmiStatus.Warning, statuses[25f])
            assertEquals(BmiStatus.Poor, statuses[30f])
        }

    // --- pagination ---

    @Test
    fun `historyItems are paginated`() =
        runTest {
            val records =
                (1..25)
                    .map { i ->
                        WeightRecordEntity(id = "w$i", timestampMs = i * 1000L, weightKg = 80f)
                    }.reversed()

            coEvery { weightRepository.getByDateRangePaged(any(), any(), 10, 0) } returns records.take(10)
            coEvery { weightRepository.getByDateRangePaged(any(), any(), 10, 10) } returns records.drop(10).take(10)
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 25

            viewModel = createViewModel()
            val state = viewModel.uiState.first { it.historyItems.isNotEmpty() }

            assertEquals(10, state.historyItems.size)
            assertEquals(3, state.totalPages)
            assertEquals(1, state.currentPage)
            assertEquals(25_000L, state.historyItems.first().timestampMs)

            viewModel.onNextPage()
            val pageTwo = viewModel.uiState.first { it.currentPage == 2 }
            assertEquals(15_000L, pageTwo.historyItems.first().timestampMs)
        }

    @Test
    fun `page resets to 1 onRangeSelected`() =
        runTest {
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 25
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(WeightRecordEntity("w", 1_000L, 80f))
            viewModel = createViewModel()
            viewModel.uiState.first { !it.isLoading }

            viewModel.onNextPage()
            var state = viewModel.uiState.first { it.currentPage == 2 }
            assertEquals(2, state.currentPage)

            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            state = viewModel.uiState.first { it.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }

    @Test
    fun `page resets to 1 on selected date change`() =
        runTest {
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 25
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(WeightRecordEntity("w", 1_000L, 80f))
            viewModel = createViewModel()
            viewModel.uiState.first { !it.isLoading }

            viewModel.onNextPage()
            var state = viewModel.uiState.first { it.currentPage == 2 }
            assertEquals(2, state.currentPage)

            selectedDateRepo.updateSelectedDate(LocalDate.now().minusDays(1))
            state = viewModel.uiState.first { it.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }

    @Test
    fun `page persists across re-subscription after WhileSubscribed timeout`() =
        runTest(testDispatcher) {
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 25
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(WeightRecordEntity("w", 1_000L, 80f))

            viewModel =
                WeightDetailViewModel(
                    weightRepository = weightRepository,
                    settingsRepo = settingsRepo,
                    selectedDateRepository = selectedDateRepo,
                    ioDispatcher = testDispatcher,
                )

            val job1 = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { !it.isLoading }
            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }

            job1.cancel()
            testScheduler.advanceUntilIdle()

            val job2 = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.currentPage)
            job2.cancel()

            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }

    @Test
    fun `last partial page renders correctly`() =
        runTest {
            val records =
                (1..15)
                    .map { i ->
                        WeightRecordEntity(id = "w$i", timestampMs = i * 1000L, weightKg = 80f)
                    }.reversed()

            coEvery { weightRepository.getByDateRangePaged(any(), any(), 10, 0) } returns records.take(10)
            coEvery { weightRepository.getByDateRangePaged(any(), any(), 10, 10) } returns records.drop(10).take(5)
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 15

            viewModel = createViewModel()
            viewModel.uiState.first { !it.isLoading }
            viewModel.onNextPage()

            val pageTwo = viewModel.uiState.first { it.currentPage == 2 }
            assertEquals(5, pageTwo.historyItems.size)
            assertEquals(5_000L, pageTwo.historyItems.first().timestampMs)
            assertEquals(2, pageTwo.totalPages)
        }

    @Test
    fun `currentPage is clamped when count drops`() =
        runTest {
            val countFlow = MutableStateFlow(25)
            coEvery { weightRepository.countByDateRange(any(), any()) } answers { countFlow.value }
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(WeightRecordEntity("w", 1_000L, 80f))

            viewModel = createViewModel()
            viewModel.uiState.first { !it.isLoading }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            val state3 = viewModel.uiState.first { it.currentPage == 3 }
            assertEquals(3, state3.currentPage)
            assertEquals(3, state3.totalPages)

            countFlow.value = 5
            viewModel.onPreviousPage()
            val clampedState = viewModel.uiState.first { it.totalPages == 1 }
            assertEquals(1, clampedState.currentPage)
            assertEquals(1, clampedState.totalPages)
        }

    @Test
    fun `onPreviousPage decrements page but not below 1`() =
        runTest {
            coEvery { weightRepository.countByDateRange(any(), any()) } returns 25
            coEvery { weightRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(WeightRecordEntity("w", 1_000L, 80f))
            viewModel = createViewModel()
            viewModel.uiState.first { !it.isLoading }

            viewModel.onNextPage()
            viewModel.uiState.first { !it.isLoading && it.currentPage == 2 }

            viewModel.onPreviousPage()
            var state = viewModel.uiState.first { !it.isLoading && it.currentPage == 1 }
            assertEquals(1, state.currentPage)

            viewModel.onPreviousPage()
            assertEquals(1, viewModel.uiState.value.currentPage)
        }

    @Test
    fun `twelve month range buckets daily weights into eight week points with a period summary`() =
        runTest {
            val start = LocalDate.of(2026, 1, 1)
            selectedDateFlow.value = start
            val zone = java.time.ZoneId.systemDefault()
            val records =
                listOf(
                    // Octad 1 (weeks 1-8): one record
                    WeightRecordEntity(
                        "o1",
                        start
                            .plusMonths(1)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli(),
                        70f,
                    ),
                    // Octad 2 (weeks 9-16): one record
                    WeightRecordEntity(
                        "o2",
                        start
                            .plusMonths(2)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli(),
                        72f,
                    ),
                    // Octad 3 (weeks 17-24): one record
                    WeightRecordEntity(
                        "o3",
                        start
                            .plusMonths(4)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli(),
                        71f,
                    ),
                )
            coEvery { weightRepository.getByDateRange(any(), any()) } returns records
            coEvery { weightRepository.getLatest() } returns records.last()
            coEvery { weightRepository.getPrevious(any()) } returns records[1]

            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(unitSystem = UnitSystem.METRIC))

            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.TWELVE_MONTHS)

            val state =
                viewModel.uiState.first {
                    it.selectedRange == TimeRange.TWELVE_MONTHS && !it.isLoading
                }

            // Only three populated octads survive bucketing.
            assertEquals(3, state.dailyWeights.count { it.value != null })
            assertEquals(listOf(70f, 72f, 71f), state.dailyWeights.filter { it.value != null }.map { it.value })
            assertEquals(TrendGranularity.EIGHT_WEEK, state.periodSummary?.granularity)
        }
}
