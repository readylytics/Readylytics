package app.readylytics.health.core.model.domain.util

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals

class WeekBoundsTest {
    // Thursday, June 4 2026 — mid-week, plain case shared by most tests below.
    private val today = LocalDate.of(2026, 6, 4)

    @Test
    fun `monday start resolves week start to the monday on or before today`() {
        assertEquals(LocalDate.of(2026, 6, 1), WeekBounds.weekStartOnOrBefore(today, DayOfWeek.MONDAY))
    }

    @Test
    fun `sunday start resolves week start to the sunday on or before today`() {
        assertEquals(LocalDate.of(2026, 5, 31), WeekBounds.weekStartOnOrBefore(today, DayOfWeek.SUNDAY))
    }

    @Test
    fun `week start equal to today returns today unchanged`() {
        assertEquals(today, WeekBounds.weekStartOnOrBefore(today, today.dayOfWeek))
    }

    @Test
    fun `monday start current week to date is monday through today`() {
        val range = WeekBounds.currentWeekToDate(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 6, 1), range.start)
        assertEquals(today, range.end)
    }

    @Test
    fun `monday start current week full spans monday through sunday`() {
        val range = WeekBounds.currentWeekFull(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 6, 1), range.start)
        assertEquals(LocalDate.of(2026, 6, 7), range.end)
    }

    @Test
    fun `sunday start current week full spans sunday through saturday`() {
        val range = WeekBounds.currentWeekFull(today, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 5, 31), range.start)
        assertEquals(LocalDate.of(2026, 6, 6), range.end)
    }

    @Test
    fun `monday start previous week full is the prior monday through sunday`() {
        val range = WeekBounds.previousWeekFull(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 5, 25), range.start)
        assertEquals(LocalDate.of(2026, 5, 31), range.end)
    }

    @Test
    fun `monday start previous week to date matches the elapsed day count of the current week`() {
        // today is Thursday (4th day of a Monday-start week): Mon..Thu = 4 days elapsed.
        val current = WeekBounds.currentWeekToDate(today, DayOfWeek.MONDAY)
        val previous = WeekBounds.previousWeekToDate(today, DayOfWeek.MONDAY)
        assertEquals(current.days, previous.days)
        assertEquals(LocalDate.of(2026, 5, 25), previous.start)
        assertEquals(LocalDate.of(2026, 5, 28), previous.end)
    }

    @Test
    fun `sunday start previous week to date matches the elapsed day count of the current week`() {
        val current = WeekBounds.currentWeekToDate(today, DayOfWeek.SUNDAY)
        val previous = WeekBounds.previousWeekToDate(today, DayOfWeek.SUNDAY)
        assertEquals(current.days, previous.days)
        assertEquals(LocalDate.of(2026, 5, 24), previous.start)
        assertEquals(LocalDate.of(2026, 5, 28), previous.end)
    }

    @Test
    fun `today equal to week start yields a single-day previous week to date range`() {
        // Saturday week start, today IS a Saturday: 0 elapsed days beyond the start itself.
        val saturday = LocalDate.of(2026, 6, 6)
        val previous = WeekBounds.previousWeekToDate(saturday, DayOfWeek.SATURDAY)
        assertEquals(LocalDate.of(2026, 5, 30), previous.start)
        assertEquals(LocalDate.of(2026, 5, 30), previous.end)
        assertEquals(1, previous.days)
    }

    @Test
    fun `first day of a new configured week resolves week start to itself`() {
        val monday = LocalDate.of(2026, 6, 1)
        assertEquals(monday, WeekBounds.weekStartOnOrBefore(monday, DayOfWeek.MONDAY))
    }

    @Test
    fun `last day of a configured week resolves week start to six days earlier`() {
        val sunday = LocalDate.of(2026, 6, 7)
        assertEquals(LocalDate.of(2026, 6, 1), WeekBounds.weekStartOnOrBefore(sunday, DayOfWeek.MONDAY))
    }

    @Test
    fun `week boundaries cross a month boundary correctly`() {
        // Monday June 1 2026 is preceded by a week starting Monday May 25 2026.
        val range = WeekBounds.previousWeekFull(LocalDate.of(2026, 6, 4), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 5, 25), range.start)
        assertEquals(LocalDate.of(2026, 5, 31), range.end)
    }

    @Test
    fun `week boundaries cross a year boundary correctly for a sunday start`() {
        // Friday Jan 2 2026; Sunday-start week begins Dec 28 2025.
        val friday = LocalDate.of(2026, 1, 2)
        val range = WeekBounds.currentWeekFull(friday, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2025, 12, 28), range.start)
        assertEquals(LocalDate.of(2026, 1, 3), range.end)
    }

    @Test
    fun `partial current week reflects only elapsed days`() {
        // Tuesday, second day of a Monday-start week.
        val tuesday = LocalDate.of(2026, 6, 2)
        val range = WeekBounds.currentWeekToDate(tuesday, DayOfWeek.MONDAY)
        assertEquals(2, range.days)
    }
}
