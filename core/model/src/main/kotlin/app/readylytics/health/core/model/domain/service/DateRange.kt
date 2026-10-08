package app.readylytics.health.core.model.domain.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A closed date range [start, end] with inclusive endpoints. */
data class DateRange(
    val start: LocalDate,
    val end: LocalDate,
) {
    /** Number of days inclusive of both endpoints. */
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    fun contains(date: LocalDate): Boolean = date in start..end
}
