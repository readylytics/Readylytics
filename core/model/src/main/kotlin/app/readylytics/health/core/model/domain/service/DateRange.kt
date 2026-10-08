package app.readylytics.health.core.model.domain.service

import app.readylytics.health.core.model.domain.model.Result
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
    object Codes {
        const val END_BEFORE_START: String = "END_BEFORE_START"
    }

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
                    code = Codes.END_BEFORE_START,
                )
            } else {
                Result.Success(DateRange(start = start, end = end))
            }
    }
}
