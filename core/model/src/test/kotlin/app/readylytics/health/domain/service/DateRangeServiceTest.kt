package app.readylytics.health.domain.service

import app.readylytics.health.domain.model.Result
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateRangeServiceTest {
    @Test
    fun `creates valid date range`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val result = DateRange.create(start, end)
        assertTrue(result is Result.Success)
        val range = result.data
        assertEquals(start, range.start)
        assertEquals(end, range.end)
    }

    @Test
    fun `rejects when end is before start`() {
        val start = LocalDate.of(2026, 6, 30)
        val end = LocalDate.of(2026, 6, 1)
        val result = DateRange.create(start, end)
        assertTrue(result is Result.Failure)
        assertEquals(DateRangeService.Codes.END_BEFORE_START, result.code)
    }

    @Test
    fun `allows equal start and end dates`() {
        val date = LocalDate.of(2026, 6, 15)
        val result = DateRange.create(date, date)
        assertTrue(result is Result.Success)
        val range = result.data
        assertEquals(1, range.days)
    }

    @Test
    fun `calculates days correctly`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val range = (DateRange.create(start, end) as Result.Success).data
        assertEquals(30, range.days)
    }

    @Test
    fun `calculates one day for same date`() {
        val date = LocalDate.of(2026, 6, 1)
        val range = (DateRange.create(date, date) as Result.Success).data
        assertEquals(1, range.days)
    }

    @Test
    fun `contains checks date membership`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val range = (DateRange.create(start, end) as Result.Success).data

        assertTrue(range.contains(LocalDate.of(2026, 6, 15)))
        assertTrue(range.contains(start))
        assertTrue(range.contains(end))
        assertFalse(range.contains(LocalDate.of(2026, 5, 31)))
        assertFalse(range.contains(LocalDate.of(2026, 7, 1)))
    }

    @Test
    fun `toDateList generates all dates in range`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 5)
        val range = (DateRange.create(start, end) as Result.Success).data
        val dates = range.toDateList()

        assertEquals(5, dates.size)
        assertEquals(start, dates[0])
        assertEquals(end, dates[4])
    }

    @Test
    fun `overlaps detects overlapping ranges`() {
        val range1 =
            (
                DateRange.create(
                    LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 15),
                ) as Result.Success
            ).data
        val range2 =
            (
                DateRange.create(
                    LocalDate.of(2026, 6, 10),
                    LocalDate.of(2026, 6, 20),
                ) as Result.Success
            ).data

        assertTrue(range1.overlaps(range2))
        assertTrue(range2.overlaps(range1))
    }

    @Test
    fun `overlaps rejects non-overlapping ranges`() {
        val range1 =
            (
                DateRange.create(
                    LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 10),
                ) as Result.Success
            ).data
        val range2 =
            (
                DateRange.create(
                    LocalDate.of(2026, 6, 20),
                    LocalDate.of(2026, 6, 30),
                ) as Result.Success
            ).data

        assertFalse(range1.overlaps(range2))
    }

    @Test
    fun `toEpochMillisRange converts to milliseconds`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 1)
        val range = (DateRange.create(start, end) as Result.Success).data
        val (startMs, endMs) = range.toEpochMillisRange()

        assertTrue(endMs > startMs)
    }

    @Test
    fun `date range week calculation`() {
        val monday = LocalDate.of(2026, 6, 1)
        val sunday = LocalDate.of(2026, 6, 7)
        val range = (DateRange.create(monday, sunday) as Result.Success).data
        assertEquals(7, range.days)
    }

    @Test
    fun `date range month calculation`() {
        val start = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 6, 30)
        val range = (DateRange.create(start, end) as Result.Success).data
        assertEquals(30, range.days)
    }

    @Test
    fun `overlaps edge case touching boundaries`() {
        val range1 =
            (
                DateRange.create(
                    LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 10),
                ) as Result.Success
            ).data
        val range2 =
            (
                DateRange.create(
                    LocalDate.of(2026, 6, 10),
                    LocalDate.of(2026, 6, 20),
                ) as Result.Success
            ).data

        assertTrue(range1.overlaps(range2))
    }
}
