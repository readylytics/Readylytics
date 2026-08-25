package app.readylytics.health.core.model.domain.util

import app.readylytics.health.core.model.domain.service.DateRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Single source of truth for "this week"/"last week" boundaries given the user's configured
 * [DayOfWeek] week start. All weekly analytics aggregation must resolve its date ranges through
 * here so every visualization agrees on the same week definition.
 *
 * "Current" ranges are anchored on [today]; "previous" ranges are always the immediately
 * preceding configured week. [currentWeekToDate]/[previousWeekToDate] compare like-for-like
 * elapsed day-counts (an in-progress current week is never compared against a full previous
 * week); [currentWeekFull]/[previousWeekFull] cover the full 7-day window, for series that need
 * a fixed-length x-axis (future current-week days are still absent from any underlying data,
 * not represented by these ranges themselves).
 */
object WeekBounds {
    private const val DAYS_IN_WEEK = 7L

    /** The configured week start on or before [date] (equal to [date] when [date] IS the start). */
    fun weekStartOnOrBefore(
        date: LocalDate,
        weekStartDay: DayOfWeek,
    ): LocalDate {
        val diff = (DAYS_IN_WEEK + date.dayOfWeek.value - weekStartDay.value) % DAYS_IN_WEEK
        return date.minusDays(diff)
    }

    /** Configured week start through [today], inclusive. Partial for an in-progress week. */
    fun currentWeekToDate(
        today: LocalDate,
        weekStartDay: DayOfWeek,
    ): DateRange = DateRange(start = weekStartOnOrBefore(today, weekStartDay), end = today)

    /** Full 7-day window containing [today]. */
    fun currentWeekFull(
        today: LocalDate,
        weekStartDay: DayOfWeek,
    ): DateRange {
        val start = weekStartOnOrBefore(today, weekStartDay)
        return DateRange(start = start, end = start.plusDays(DAYS_IN_WEEK - 1))
    }

    /** Full 7-day previous configured week, entirely in the past. */
    fun previousWeekFull(
        today: LocalDate,
        weekStartDay: DayOfWeek,
    ): DateRange {
        val start = weekStartOnOrBefore(today, weekStartDay).minusWeeks(1)
        return DateRange(start = start, end = start.plusDays(DAYS_IN_WEEK - 1))
    }

    /** Previous configured week, truncated to the same elapsed day-count as [currentWeekToDate]. */
    fun previousWeekToDate(
        today: LocalDate,
        weekStartDay: DayOfWeek,
    ): DateRange {
        val currentStart = weekStartOnOrBefore(today, weekStartDay)
        val elapsedDays = ChronoUnit.DAYS.between(currentStart, today)
        val previousStart = currentStart.minusWeeks(1)
        return DateRange(start = previousStart, end = previousStart.plusDays(elapsedDays))
    }
}
