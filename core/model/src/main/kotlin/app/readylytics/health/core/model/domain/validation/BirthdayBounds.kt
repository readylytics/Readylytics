package app.readylytics.health.core.model.domain.validation

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Single source of truth for which birthdays are acceptable: not before [minYear], not after
 * [maxDate] (today in the clock's zone). Pure Kotlin so view models, validation rules and the
 * date-picker UI all derive their limits from the same value.
 */
data class BirthdayBounds(
    val minYear: Int,
    val maxDate: LocalDate,
) {
    val yearRange: IntRange get() = minYear..maxDate.year

    operator fun contains(date: LocalDate): Boolean = date.year >= minYear && !date.isAfter(maxDate)

    fun isSelectableYear(year: Int): Boolean = year in yearRange

    /** The Material date picker reports a date as the UTC-midnight epoch millis of that date. */
    fun isSelectableUtcMillis(utcMillis: Long): Boolean =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate() in this

    companion object {
        fun at(clock: Clock): BirthdayBounds = BirthdayBounds(SettingsValidators.MIN_BIRTH_YEAR, LocalDate.now(clock))
    }
}
