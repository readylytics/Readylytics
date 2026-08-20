package app.readylytics.health.feature.vitals.bodyfat

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.BodyFatCategory
import app.readylytics.health.domain.model.BodyFatRecord
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.WeightRecord
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.domain.preferences.PhysiologyProfile
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.BodyFatRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private fun BodyFatRecordEntity(
    id: String,
    timestampMs: Long,
    bodyFatPercent: Float,
    deviceName: String? = null,
): BodyFatRecord = BodyFatRecord(id, Instant.ofEpochMilli(timestampMs), bodyFatPercent, deviceName)

private fun WeightRecordEntity(
    id: String,
    timestampMs: Long,
    weightKg: Float,
    deviceName: String? = null,
): WeightRecord = WeightRecord(id, Instant.ofEpochMilli(timestampMs), weightKg, deviceName)

@OptIn(ExperimentalCoroutinesApi::class)
class BodyFatDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: BodyFatDetailViewModel
    private lateinit var bodyFatRepository: BodyFatRepository
    private lateinit var weightRepository: WeightRepository
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var selectedDateRepo: SelectedDateStore

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        bodyFatRepository =
            mockk {
                coEvery { getByDateRange(any(), any()) } returns emptyList()
                coEvery { getByDateRangePaged(any(), any(), any(), any()) } returns emptyList()
                coEvery { countByDateRange(any(), any()) } returns 0
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
                    selectedDateFlow.value = firstArg<LocalDate>()
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
    fun `male reference metadata is exposed without calling the scale optimal`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(age = 30, gender = Gender.MALE),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.referenceAxisMaximum > 0f }
            assertEquals(2f, state.referenceAxisMinimum)
            assertEquals(25f, state.referenceAxisMaximum)
            assertEquals(15.5f, state.referenceMidpoint)
            assertEquals(Gender.MALE, state.gender)
        }

    @Test
    fun `female reference metadata is age independent`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(age = 50, gender = Gender.FEMALE),
                )

            viewModel = createViewModel()

            val state = viewModel.uiState.first { it.referenceAxisMaximum > 0f }
            assertEquals(10f, state.referenceAxisMinimum)
            assertEquals(32f, state.referenceAxisMaximum)
            assertEquals(22.5f, state.referenceMidpoint)
            assertEquals(Gender.FEMALE, state.gender)
        }

    @Test
    fun `latest body fat status matches canonical male assessment at 2 percent`() =
        runTest {
            val record = BodyFatRecordEntity("male", System.currentTimeMillis(), 2f)
            coEvery { bodyFatRepository.getLatest() } returns record
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.ACTIVE, gender = Gender.MALE))
            viewModel = createViewModel()

            assertEquals(
                BodyCompositionAssessment
                    .assessBodyFat(2f, PhysiologyProfile.ACTIVE, Gender.MALE)
                    .status
                    .toMetricStatus(),
                viewModel.uiState.first { it.bodyFatStatus != null }.bodyFatStatus,
            )
        }

    @Test
    fun `latest body fat status matches canonical female assessment at 10 percent`() =
        runTest {
            val record = BodyFatRecordEntity("female", System.currentTimeMillis(), 10f)
            coEvery { bodyFatRepository.getLatest() } returns record
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.ACTIVE, gender = Gender.FEMALE))
            viewModel = createViewModel()

            assertEquals(
                BodyCompositionAssessment
                    .assessBodyFat(10f, PhysiologyProfile.ACTIVE, Gender.FEMALE)
                    .status
                    .toMetricStatus(),
                viewModel.uiState.first { it.bodyFatStatus != null }.bodyFatStatus,
            )
        }

    @Test
    fun `latest body fat status matches canonical unset-gender assessment at 10 percent`() =
        runTest {
            val record = BodyFatRecordEntity("unset-at-minimum", System.currentTimeMillis(), 10f)
            coEvery { bodyFatRepository.getLatest() } returns record
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.ACTIVE, gender = null))
            viewModel = createViewModel()

            assertEquals(
                BodyCompositionAssessment
                    .assessBodyFat(10f, PhysiologyProfile.ACTIVE, null)
                    .status
                    .toMetricStatus(),
                viewModel.uiState.first { it.bodyFatStatus != null }.bodyFatStatus,
            )
        }

    @Test
    fun `latest body fat status matches canonical unset-gender assessment above reference`() =
        runTest {
            val record = BodyFatRecordEntity("unset-above", System.currentTimeMillis(), 30.01f)
            coEvery { bodyFatRepository.getLatest() } returns record
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.ACTIVE, gender = null))
            viewModel = createViewModel()

            assertEquals(
                BodyCompositionAssessment
                    .assessBodyFat(30.01f, PhysiologyProfile.ACTIVE, null)
                    .status
                    .toMetricStatus(),
                viewModel.uiState.first { it.bodyFatStatus != null }.bodyFatStatus,
            )
        }

    // --- historyItems ---

    @Test
    fun `historyItems include lean mass when same-day weight record exists`() =
        runTest {
            val now = System.currentTimeMillis()
            val bodyFatRecord = BodyFatRecordEntity(id = "1", timestampMs = now, bodyFatPercent = 14.2f)
            val weightRecord = WeightRecordEntity(id = "1", timestampMs = now, weightKg = 78.4f)
            coEvery { bodyFatRepository.getByDateRange(any(), any()) } returns listOf(bodyFatRecord)
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(bodyFatRecord)
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 1
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
            assertEquals(BodyFatCategory.FITNESS, item.category)
        }

    @Test
    fun `historyItems use canonical male assessment at 2 percent`() =
        runTest {
            val record = BodyFatRecordEntity("male-essential", System.currentTimeMillis(), 2f)
            coEvery { bodyFatRepository.getByDateRange(any(), any()) } returns listOf(record)
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(record)
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 1
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.ACTIVE, gender = Gender.MALE))
            viewModel = createViewModel()

            val item =
                viewModel.uiState
                    .first { it.historyItems.isNotEmpty() }
                    .historyItems
                    .single()

            assertEquals(
                BodyCompositionAssessment
                    .assessBodyFat(2f, PhysiologyProfile.ACTIVE, Gender.MALE)
                    .status
                    .toMetricStatus(),
                item.status,
            )
            assertEquals(BodyFatCategory.ESSENTIAL, item.category)
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
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(bodyFatRecord)
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 1
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
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns listOf(bodyFatRecord)
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 1
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

    // --- pagination ---

    @Test
    fun `historyItems are paginated`() =
        runTest {
            val records =
                (1..25)
                    .map { i ->
                        BodyFatRecordEntity(id = "bf$i", timestampMs = i * 1000L, bodyFatPercent = 20f)
                    }.reversed()

            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), 10, 0) } returns records.take(10)
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), 10, 10) } returns records.drop(10).take(10)
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 25

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
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 25
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(BodyFatRecordEntity("bf", 1_000L, 20f))
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
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 25
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(BodyFatRecordEntity("bf", 1_000L, 20f))
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
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 25
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(BodyFatRecordEntity("bf", 1_000L, 20f))

            viewModel =
                BodyFatDetailViewModel(
                    bodyFatRepository = bodyFatRepository,
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
                        BodyFatRecordEntity(id = "bf$i", timestampMs = i * 1000L, bodyFatPercent = 20f)
                    }.reversed()

            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), 10, 0) } returns records.take(10)
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), 10, 10) } returns records.drop(10).take(5)
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 15

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
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } answers { countFlow.value }
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(BodyFatRecordEntity("bf", 1_000L, 20f))

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
            coEvery { bodyFatRepository.countByDateRange(any(), any()) } returns 25
            coEvery { bodyFatRepository.getByDateRangePaged(any(), any(), any(), any()) } returns
                listOf(BodyFatRecordEntity("bf", 1_000L, 20f))
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
    fun `onRangeSelected updates selectedRange`() =
        runTest {
            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            val state = viewModel.uiState.first { it.selectedRange == TimeRange.THIRTY_DAYS }
            assertEquals(TimeRange.THIRTY_DAYS, state.selectedRange)
        }

    @Test
    fun `twelve month range buckets body fat into eight week points`() =
        runTest {
            val start = LocalDate.of(2026, 1, 1)
            selectedDateFlow.value = start
            val zone = java.time.ZoneId.systemDefault()
            val records =
                listOf(
                    // Octad 1 (weeks 1-8): one record
                    BodyFatRecordEntity(
                        "o1",
                        start
                            .plusMonths(1)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli(),
                        19f,
                    ),
                    // Octad 2 (weeks 9-16): one record
                    BodyFatRecordEntity(
                        "o2",
                        start
                            .plusMonths(2)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli(),
                        21f,
                    ),
                    // Octad 3 (weeks 17-24): one record
                    BodyFatRecordEntity(
                        "o3",
                        start
                            .plusMonths(4)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli(),
                        22f,
                    ),
                )
            coEvery { bodyFatRepository.getByDateRange(any(), any()) } returns records
            coEvery { bodyFatRepository.getLatest() } returns records.last()
            coEvery { bodyFatRepository.getPrevious(any()) } returns records[1]

            viewModel = createViewModel()
            viewModel.onRangeSelected(TimeRange.TWELVE_MONTHS)

            val state =
                viewModel.uiState.first {
                    it.selectedRange == TimeRange.TWELVE_MONTHS && !it.isLoading
                }

            // Three populated octads: 19.0, 21.0, 22.0 (valueDecimalPlaces = 1).
            assertEquals(3, state.dailyBodyFat.count { it.value != null })
            assertEquals(listOf(19f, 21f, 22f), state.dailyBodyFat.filter { it.value != null }.map { it.value })
            assertEquals(TrendGranularity.EIGHT_WEEK, state.periodSummary?.granularity)
        }
}
