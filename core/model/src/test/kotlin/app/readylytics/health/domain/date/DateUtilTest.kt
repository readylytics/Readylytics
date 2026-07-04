package app.readylytics.health.domain.date

import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

class DateUtilTest {
    @Test
    fun `date range days between`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val days = ChronoUnit.DAYS.between(start, end)
        assertEquals(29, days)
    }

    @Test
    fun `last 7 days from date`() {
        val today = LocalDate.now()
        val sevenDaysAgo = today.minusDays(6)
        val days = ChronoUnit.DAYS.between(sevenDaysAgo, today).toInt() + 1
        assertEquals(7, days)
    }

    @Test
    fun `month days calculation`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val days = ChronoUnit.DAYS.between(start, end) + 1
        assertEquals(30, days)
    }

    @Test
    fun `week days calculation`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 7)
        val days = ChronoUnit.DAYS.between(start, end) + 1
        assertEquals(7, days)
    }

    @Test
    fun `date comparison and ordering`() {
        val date1 = LocalDate.of(2026, 6, 1)
        val date2 = LocalDate.of(2026, 6, 15)
        assertEquals(true, date1.isBefore(date2))
        assertEquals(false, date2.isBefore(date1))
    }

    @Test
    fun `date addition subtraction`() {
        val date = LocalDate.of(2026, 6, 1)
        val tomorrow = date.plusDays(1)
        val yesterday = date.minusDays(1)
        assertEquals(LocalDate.of(2026, 6, 2), tomorrow)
        assertEquals(LocalDate.of(2026, 5, 31), yesterday)
    }

    @Test
    fun `year boundary dates`() {
        val yearStart = LocalDate.of(2026, 1, 1)
        val yearEnd = LocalDate.of(2026, 12, 31)
        val daysInYear = ChronoUnit.DAYS.between(yearStart, yearEnd) + 1
        assertEquals(365, daysInYear)
    }

    @Test
    fun `month name and values`() {
        val june = LocalDate.of(2026, 6, 15)
        assertEquals(6, june.monthValue)
    }
}
