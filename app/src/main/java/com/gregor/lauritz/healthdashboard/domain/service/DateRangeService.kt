package com.gregor.lauritz.healthdashboard.domain.service

import com.gregor.lauritz.healthdashboard.domain.model.Result
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A closed date range [start, end] with inclusive endpoints.
 *
 * Pure value object; constructed via [DateRange.create] which returns a [Result] so
 * invariant violations surface as [Result.Failure] instead of exceptions.
 */
data class DateRange(
    val start: LocalDate,
    val end: LocalDate,
) {
    /** Number of days inclusive of both endpoints. */
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun toDateList(): List<LocalDate> = (0 until days).map { start.plusDays(it.toLong()) }

    fun overlaps(other: DateRange): Boolean = !end.isBefore(other.start) && !start.isAfter(other.end)

    fun toEpochMillisRange(zoneId: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val startMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs =
            end
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli() - 1
        return Pair(startMs, endMs)
    }

    companion object {
        /** Safe factory: returns [Result.Failure] when `end` is before `start`. */
        fun create(
            start: LocalDate,
            end: LocalDate,
        ): Result<DateRange> =
            if (end.isBefore(start)) {
                Result.Failure(
                    reason = "end ($end) must not be before start ($start)",
                    code = DateRangeService.Codes.END_BEFORE_START,
                )
            } else {
                Result.Success(DateRange(start = start, end = end))
            }
    }
}

/**
 * Pure-Kotlin service for date-window calculations used by health scoring.
 *
 * Constructor-injectable; takes an optional [Clock] for deterministic testing.
 * Range builders return [Result]s (no exceptions); arithmetic helpers remain direct
 * since they cannot fail.
 */
class DateRangeService(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** Today, according to the injected [clock]. */
    fun today(): LocalDate = LocalDate.now(clock)

    /** A range ending today (inclusive) covering [days] total days. Requires `days >= 1`. */
    fun rangeEndingToday(days: Int): Result<DateRange> {
        if (days < 1) return Result.Failure("days must be >= 1 (was $days)", Codes.NON_POSITIVE_DAYS)
        val end = today()
        return DateRange.create(start = end.minusDays((days - 1).toLong()), end = end)
    }

    /** A range ending at the given [end] (inclusive) covering [days] total days. */
    fun rangeEnding(
        end: LocalDate,
        days: Int,
    ): Result<DateRange> {
        if (days < 1) return Result.Failure("days must be >= 1 (was $days)", Codes.NON_POSITIVE_DAYS)
        return DateRange.create(start = end.minusDays((days - 1).toLong()), end = end)
    }

    /** Standard 30-day physiological baseline window ending today. */
    fun baselineWindow(): Result<DateRange> = rangeEndingToday(BASELINE_DAYS)

    /** Acute (7-day) load window ending today. */
    fun acuteWindow(): Result<DateRange> = rangeEndingToday(ACUTE_DAYS)

    /** Chronic (42-day) load window ending today. */
    fun chronicWindow(): Result<DateRange> = rangeEndingToday(CHRONIC_DAYS)

    /**
     * "Calibrating" iff the available number of distinct day-stamped readings is
     * fewer than the minimum required for stable baselines.
     */
    fun isCalibrating(distinctDaysAvailable: Int): Boolean = distinctDaysAvailable < MIN_BASELINE_DAYS

    /** Number of days in the range. */
    fun daysIn(range: DateRange): Int = range.days

    /** Number of days between two [LocalDate]s (positive if `b` is after `a`). */
    fun daysBetween(
        a: LocalDate,
        b: LocalDate,
    ): Long = ChronoUnit.DAYS.between(a, b)

    /** True iff [date] falls within [range] (inclusive on both endpoints). */
    fun isWithin(
        date: LocalDate,
        range: DateRange,
    ): Boolean = range.contains(date)

    /** All dates in the range, ascending. */
    fun expandToDates(range: DateRange): List<LocalDate> = range.toDateList()

    /** Convert an epoch-millis instant to a [LocalDate] using the injected clock's zone. */
    fun toLocalDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(clock.zone).toLocalDate()

    /** Midnight epoch millis for the given [date] in the injected clock's zone. */
    fun startOfDayMillis(date: LocalDate): Long = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()

    /** Last millisecond of the given [date] in the injected clock's zone. */
    fun endOfDayMillis(date: LocalDate): Long =
        date
            .plusDays(1)
            .atStartOfDay(clock.zone)
            .toInstant()
            .toEpochMilli() - 1

    /** Truncate an arbitrary epoch-millis instant to the start of its calendar day. */
    fun truncateToDay(epochMillis: Long): Long = startOfDayMillis(toLocalDate(epochMillis))

    /** Whole-day duration of a range. */
    fun durationOf(range: DateRange): Duration = Duration.ofDays(range.days.toLong())

    /** Sliding windows of [windowDays] across [range], stepping by one day. */
    fun slidingWindows(
        range: DateRange,
        windowDays: Int,
    ): Result<List<DateRange>> {
        if (windowDays < 1) {
            return Result.Failure("windowDays must be >= 1 (was $windowDays)", Codes.NON_POSITIVE_DAYS)
        }
        if (windowDays > range.days) return Result.Success(emptyList())
        val starts = range.toDateList().dropLast(windowDays - 1)
        val windows =
            starts.mapNotNull { s ->
                // DateRange.create only fails when end < start, which cannot happen here.
                (DateRange.create(s, s.plusDays((windowDays - 1).toLong())) as? Result.Success)?.data
            }
        return Result.Success(windows)
    }

    /** Stable [Result.Failure.code] values produced by this service. */
    object Codes {
        const val NON_POSITIVE_DAYS: String = "NON_POSITIVE_DAYS"
        const val END_BEFORE_START: String = "END_BEFORE_START"
    }

    companion object {
        const val BASELINE_DAYS: Int = 30
        const val ACUTE_DAYS: Int = 7
        const val CHRONIC_DAYS: Int = 42
        const val MIN_BASELINE_DAYS: Int = 7
    }
}
