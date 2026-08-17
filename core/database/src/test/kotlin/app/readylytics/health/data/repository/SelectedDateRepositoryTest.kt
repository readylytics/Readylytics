package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SelectedDateRepositoryTest {
    private lateinit var repository: SelectedDateRepository
    private val dao: DailySummaryDao =
        mockk {
            every { observeEarliestDateMs() } returns flowOf(null)
        }
    private val testScope = CoroutineScope(UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        repository = SelectedDateRepository(dao = dao, appScope = testScope)
    }

    @Test
    fun `resetToToday sets date to current date`() =
        runTest {
            repository.resetToToday()
            assertEquals(LocalDate.now(), repository.selectedDate.value)
        }

    @Test
    fun `advanceTodayIfNeeded does nothing when already on today and day unchanged`() =
        runTest {
            repository.advanceTodayIfNeeded()
            assertEquals(LocalDate.now(), repository.selectedDate.value)
        }

    @Test
    fun `advanceTodayIfNeeded leaves explicit past-date selection alone when day unchanged`() =
        runTest {
            val pastDate = LocalDate.now().minusDays(3)
            repository.updateSelectedDate(pastDate)

            repository.advanceTodayIfNeeded()

            assertEquals(pastDate, repository.selectedDate.value)
        }

    @Test
    fun `advanceTodayIfNeeded leaves explicit past-date selection alone across repeated foreground events`() =
        runTest {
            val pastDate = LocalDate.now().minusDays(2)
            repository.updateSelectedDate(pastDate)

            repeat(5) { repository.advanceTodayIfNeeded() }

            assertEquals(pastDate, repository.selectedDate.value)
        }

    @Test
    fun `updateSelectedDate prevents future dates`() =
        runTest {
            val futureDate = LocalDate.now().plusDays(10)
            repository.updateSelectedDate(futureDate)
            assertEquals(LocalDate.now(), repository.selectedDate.value)
        }

    @Test
    fun `updateSelectedDate allows past dates`() =
        runTest {
            val pastDate = LocalDate.now().minusDays(5)
            repository.updateSelectedDate(pastDate)
            assertEquals(pastDate, repository.selectedDate.value)
        }

    @Test
    fun `selectNextDay increments date when not today`() =
        runTest {
            val pastDate = LocalDate.now().minusDays(5)
            repository.updateSelectedDate(pastDate)
            repository.selectNextDay()
            assertEquals(pastDate.plusDays(1), repository.selectedDate.value)
        }

    @Test
    fun `selectNextDay does nothing when date is today`() =
        runTest {
            val today = LocalDate.now()
            repository.updateSelectedDate(today)
            repository.selectNextDay()
            assertEquals(today, repository.selectedDate.value)
        }

    @Test
    fun `selectPreviousDay decrements date`() =
        runTest {
            val date = LocalDate.now().minusDays(3)
            repository.updateSelectedDate(date)
            repository.selectPreviousDay()
            assertEquals(date.minusDays(1), repository.selectedDate.value)
        }

    @Test
    fun `concurrent resetToToday calls maintain consistency`() =
        runTest {
            val jobs =
                (1..100).map {
                    launch {
                        repository.resetToToday()
                    }
                }
            jobs.forEach { it.join() }
            assertEquals(LocalDate.now(), repository.selectedDate.value)
        }

    @Test
    fun `concurrent date operations maintain valid state`() =
        runTest {
            val pastDate = LocalDate.now().minusDays(10)
            repository.updateSelectedDate(pastDate)

            val jobs =
                (1..50).map { i ->
                    launch {
                        when (i % 3) {
                            0 -> repository.selectNextDay()
                            1 -> repository.selectPreviousDay()
                            else -> repository.resetToToday()
                        }
                    }
                }
            jobs.forEach { it.join() }

            val finalDate = repository.selectedDate.value
            assert(finalDate <= LocalDate.now()) { "Final date should not be in future" }
        }

    @Test
    fun `concurrent updateSelectedDate and resetToToday race handling`() =
        runTest {
            val jobs =
                (1..50).map { i ->
                    launch {
                        if (i % 2 == 0) {
                            repository.resetToToday()
                        } else {
                            repository.updateSelectedDate(LocalDate.now().minusDays(1))
                        }
                    }
                }
            jobs.forEach { it.join() }

            val finalDate = repository.selectedDate.value
            assert(
                finalDate == LocalDate.now() || finalDate == LocalDate.now().minusDays(1),
            ) {
                "Final date should be either today or yesterday"
            }
        }

    // --- earliestDate boundary tests ---

    private fun repositoryWithEarliestDate(earliest: LocalDate): SelectedDateRepository {
        val epochMs =
            earliest
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val daoWithEarliest: DailySummaryDao =
            mockk {
                every { observeEarliestDateMs() } returns flowOf(epochMs)
            }
        return SelectedDateRepository(dao = daoWithEarliest, appScope = testScope)
    }

    @Test
    fun `selectPreviousDay does nothing when at earliest date`() =
        runTest {
            val earliest = LocalDate.now().minusDays(5)
            val repo = repositoryWithEarliestDate(earliest)
            repo.updateSelectedDate(earliest)

            repo.selectPreviousDay()
            assertEquals(earliest, repo.selectedDate.value)
        }

    @Test
    fun `selectPreviousDay works when above earliest date`() =
        runTest {
            val earliest = LocalDate.now().minusDays(10)
            val repo = repositoryWithEarliestDate(earliest)
            val startDate = earliest.plusDays(2)
            repo.updateSelectedDate(startDate)

            repo.selectPreviousDay()
            assertEquals(startDate.minusDays(1), repo.selectedDate.value)
        }

    @Test
    fun `updateSelectedDate clamps to earliest date`() =
        runTest {
            val earliest = LocalDate.now().minusDays(5)
            val repo = repositoryWithEarliestDate(earliest)

            repo.updateSelectedDate(earliest.minusDays(3))
            assertEquals(earliest, repo.selectedDate.value)
        }

    @Test
    fun `earliestDate combines all DAOs minimum times`() =
        runTest {
            val date1 = LocalDate.now().minusDays(10)
            val date2 = LocalDate.now().minusDays(15)
            val date3 = LocalDate.now().minusDays(5)

            val ms1 = date1.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val ms2 = date2.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val ms3 = date3.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val mockDailySummaryDao: DailySummaryDao =
                mockk {
                    every { observeEarliestDateMs() } returns flowOf(ms1)
                }
            val mockSleepDao: SleepSessionDao =
                mockk {
                    every { observeEarliestSessionTime() } returns flowOf(ms2)
                }
            val mockHrDao: HeartRateDao =
                mockk {
                    every { observeEarliestHrTime() } returns flowOf(ms3)
                }
            val mockHrvDao: HrvDao =
                mockk {
                    every { observeEarliestHrvTime() } returns flowOf(null)
                }
            val mockSpo2Dao: OxygenSaturationRecordDao =
                mockk {
                    every { observeEarliestSpo2Time() } returns flowOf(null)
                }
            val mockBpDao: BloodPressureRecordDao =
                mockk {
                    every { observeEarliestBpTime() } returns flowOf(null)
                }

            val repo =
                SelectedDateRepository(
                    dao = mockDailySummaryDao,
                    sleepSessionDao = mockSleepDao,
                    heartRateDao = mockHrDao,
                    hrvDao = mockHrvDao,
                    oxygenSaturationRecordDao = mockSpo2Dao,
                    bloodPressureRecordDao = mockBpDao,
                    appScope = testScope,
                )

            assertEquals(date2, repo.earliestDate.value)
        }

    @Test
    fun `selectedDate is coerced to earliestDate when earliestDate shifts forward`() =
        runTest {
            val dateBefore = LocalDate.now().minusDays(15)
            val dateAfter = LocalDate.now().minusDays(5)

            val msBefore = dateBefore.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val msAfter = dateAfter.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val earliestFlow = MutableStateFlow<Long?>(msBefore)

            val mockDailySummaryDao: DailySummaryDao =
                mockk {
                    every { observeEarliestDateMs() } returns earliestFlow
                }

            val repo =
                SelectedDateRepository(
                    dao = mockDailySummaryDao,
                    appScope = testScope,
                )

            repo.updateSelectedDate(dateBefore)
            assertEquals(dateBefore, repo.selectedDate.value)

            earliestFlow.value = msAfter

            assertEquals(dateAfter, repo.selectedDate.value)
        }
}
