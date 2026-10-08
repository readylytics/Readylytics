package app.readylytics.health.workers

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.RetentionBounds
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetainedHistoryCoverageTest {
    @Test
    fun `berlin spring dst boundary resolves March 30 and pins retention coverage`() {
        val clock = Clock.fixed(Instant.parse("2026-03-29T22:30:00Z"), ZoneId.of("UTC"))
        val prefs =
            UserPreferences(
                scoringZoneId = "Europe/Berlin",
                retentionDaysEnabled = true,
                retentionDays = 30,
            )
        val today = LocalDate.now(clock.withZone(prefs.scoringZone()))
        assertEquals(LocalDate.of(2026, 3, 30), today)

        val fullRange =
            ScoreInvalidation.AffectedRange(
                start = RetentionBounds.resolveResyncStartDate(prefs, today),
                endInclusive = today,
            )
        assertEquals(LocalDate.of(2026, 3, 30).minusDays(30), fullRange.start)

        assertTrue(coversRetainedHistory(true, fullRange, prefs, today))
        assertFalse(coversRetainedHistory(true, fullRange.copy(start = fullRange.start.plusDays(1)), prefs, today))
        assertFalse(coversRetainedHistory(true, fullRange.copy(endInclusive = today.minusDays(1)), prefs, today))
        assertTrue(coversRetainedHistory(false, fullRange, prefs, today))
        assertTrue(coversRetainedHistory(true, null, prefs, today))
    }

    @Test
    fun `berlin autumn dst boundary resolves October 26 and pins retention coverage`() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T23:30:00Z"), ZoneId.of("UTC"))
        val prefs =
            UserPreferences(
                scoringZoneId = "Europe/Berlin",
                retentionDaysEnabled = true,
                retentionDays = 30,
            )
        val today = LocalDate.now(clock.withZone(prefs.scoringZone()))
        assertEquals(LocalDate.of(2026, 10, 26), today)

        val fullRange =
            ScoreInvalidation.AffectedRange(
                start = RetentionBounds.resolveResyncStartDate(prefs, today),
                endInclusive = today,
            )
        assertEquals(LocalDate.of(2026, 10, 26).minusDays(30), fullRange.start)

        assertTrue(coversRetainedHistory(true, fullRange, prefs, today))
        assertFalse(coversRetainedHistory(true, fullRange.copy(start = fullRange.start.plusDays(1)), prefs, today))
        assertFalse(coversRetainedHistory(true, fullRange.copy(endInclusive = today.minusDays(1)), prefs, today))
        assertTrue(coversRetainedHistory(false, fullRange, prefs, today))
        assertTrue(coversRetainedHistory(true, null, prefs, today))
    }

    @Test
    fun `disabled retention pins retention coverage using ABSOLUTE_MAX_DAYS`() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T23:30:00Z"), ZoneId.of("UTC"))
        val prefs =
            UserPreferences(
                scoringZoneId = "Europe/Berlin",
                retentionDaysEnabled = false,
                retentionDays = 30,
            )
        val today = LocalDate.now(clock.withZone(prefs.scoringZone()))
        val retentionStart = RetentionBounds.resolveResyncStartDate(prefs, today)
        assertEquals(today.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS), retentionStart)

        val fullRange = ScoreInvalidation.AffectedRange(start = retentionStart, endInclusive = today)

        assertTrue(coversRetainedHistory(true, fullRange, prefs, today))
        assertFalse(coversRetainedHistory(true, fullRange.copy(start = fullRange.start.plusDays(1)), prefs, today))
        assertFalse(coversRetainedHistory(true, fullRange.copy(endInclusive = today.minusDays(1)), prefs, today))
        assertTrue(coversRetainedHistory(false, fullRange, prefs, today))
        assertTrue(coversRetainedHistory(true, null, prefs, today))
    }

    @Test
    fun `host timezone changes do not alter scoring-zone date derivation or retention coverage`() {
        val defaultTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val clock = Clock.fixed(Instant.parse("2026-03-29T22:30:00Z"), ZoneId.of("UTC"))
            val prefs =
                UserPreferences(
                    scoringZoneId = "Europe/Berlin",
                    retentionDaysEnabled = true,
                    retentionDays = 30,
                )
            val today = LocalDate.now(clock.withZone(prefs.scoringZone()))
            assertEquals(LocalDate.of(2026, 3, 30), today)

            val fullRange =
                ScoreInvalidation.AffectedRange(
                    start = RetentionBounds.resolveResyncStartDate(prefs, today),
                    endInclusive = today,
                )
            assertTrue(coversRetainedHistory(true, fullRange, prefs, today))
        } finally {
            TimeZone.setDefault(defaultTz)
        }
    }
}
