package app.readylytics.health.core.model.domain.validation

import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BirthdayBoundsTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC)
    private val bounds = BirthdayBounds.at(clock)
    private val utcMidnight = { date: LocalDate -> date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }

    @Test
    fun `yearRange spans 1900 to clock year`() {
        assertEquals(1900..2026, bounds.yearRange)
    }

    @Test
    fun `contains accepts 1900-01-01 and rejects 1899-12-31`() {
        assertTrue(LocalDate.of(1900, 1, 1) in bounds)
        assertFalse(LocalDate.of(1899, 12, 31) in bounds)
    }

    @Test
    fun `contains accepts today and rejects tomorrow`() {
        assertTrue(LocalDate.of(2026, 6, 15) in bounds)
        assertFalse(LocalDate.of(2026, 6, 16) in bounds)
    }

    @Test
    fun `year boundary uses clock zone`() {
        val ahead = BirthdayBounds.at(Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneOffset.ofHours(14)))
        assertEquals(LocalDate.of(2027, 1, 1), ahead.maxDate)
        assertTrue(LocalDate.of(2027, 1, 1) in ahead)
        assertFalse(LocalDate.of(2027, 1, 2) in ahead)
        assertEquals(2027, ahead.yearRange.last)
    }

    @Test
    fun `isSelectableYear covers 1899 1900 current and next year`() {
        assertEquals(
            listOf(false, true, true, false),
            listOf(1899, 1900, 2026, 2027).map(bounds::isSelectableYear),
        )
    }

    @Test
    fun `isSelectableUtcMillis accepts UTC midnight of maxDate and rejects the next day and pre-1900`() {
        assertTrue(bounds.isSelectableUtcMillis(utcMidnight(bounds.maxDate)))
        assertFalse(bounds.isSelectableUtcMillis(utcMidnight(bounds.maxDate) + 86_400_000L))
        assertFalse(bounds.isSelectableUtcMillis(utcMidnight(LocalDate.of(1899, 12, 31))))
        assertTrue(bounds.isSelectableUtcMillis(utcMidnight(LocalDate.of(1900, 1, 1))))
    }

    @Test
    fun `SettingsValidators birthdayBounds delegates to BirthdayBounds at`() {
        assertEquals(BirthdayBounds.at(clock), SettingsValidators.birthdayBounds(clock))
        assertEquals(1900, SettingsValidators.MIN_BIRTH_YEAR)
    }
}
