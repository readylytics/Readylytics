package com.gregor.lauritz.healthdashboard.data.repository

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class SelectedDateRepositoryTest {
    private lateinit var repository: SelectedDateRepository

    @Before
    fun setUp() {
        repository = SelectedDateRepository()
    }

    @Test
    fun `resetToToday sets date to current date`() = runTest {
        repository.resetToToday()
        assertEquals(LocalDate.now(), repository.selectedDate.value)
    }

    @Test
    fun `updateSelectedDate prevents future dates`() = runTest {
        val futureDate = LocalDate.now().plusDays(10)
        repository.updateSelectedDate(futureDate)
        assertEquals(LocalDate.now(), repository.selectedDate.value)
    }

    @Test
    fun `updateSelectedDate allows past dates`() = runTest {
        val pastDate = LocalDate.now().minusDays(5)
        repository.updateSelectedDate(pastDate)
        assertEquals(pastDate, repository.selectedDate.value)
    }

    @Test
    fun `selectNextDay increments date when not today`() = runTest {
        val pastDate = LocalDate.now().minusDays(5)
        repository.updateSelectedDate(pastDate)
        repository.selectNextDay()
        assertEquals(pastDate.plusDays(1), repository.selectedDate.value)
    }

    @Test
    fun `selectNextDay does nothing when date is today`() = runTest {
        val today = LocalDate.now()
        repository.updateSelectedDate(today)
        repository.selectNextDay()
        assertEquals(today, repository.selectedDate.value)
    }

    @Test
    fun `selectPreviousDay decrements date`() = runTest {
        val date = LocalDate.now().minusDays(3)
        repository.updateSelectedDate(date)
        repository.selectPreviousDay()
        assertEquals(date.minusDays(1), repository.selectedDate.value)
    }

    @Test
    fun `concurrent resetToToday calls maintain consistency`() = runTest {
        val jobs = (1..100).map {
            launch {
                repository.resetToToday()
            }
        }
        jobs.forEach { it.join() }
        assertEquals(LocalDate.now(), repository.selectedDate.value)
    }

    @Test
    fun `concurrent date operations maintain valid state`() = runTest {
        val pastDate = LocalDate.now().minusDays(10)
        repository.updateSelectedDate(pastDate)

        val jobs = (1..50).map { i ->
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
    fun `concurrent updateSelectedDate and resetToToday race handling`() = runTest {
        val jobs = (1..50).map { i ->
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
}
