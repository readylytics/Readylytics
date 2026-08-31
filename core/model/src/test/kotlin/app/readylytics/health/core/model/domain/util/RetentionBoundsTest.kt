package app.readylytics.health.core.model.domain.util

import app.readylytics.health.core.model.data.preferences.UserPreferences
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetentionBoundsTest {
    private val today = LocalDate.of(2026, 6, 5)

    @Test
    fun `enabled retention resolves start date to today minus retentionDays`() {
        val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 365)
        assertEquals(today.minusDays(365), RetentionBounds.resolveResyncStartDate(prefs, today))
    }

    @Test
    fun `disabled retention falls back to the absolute max window`() {
        val prefs = UserPreferences(retentionDaysEnabled = false, retentionDays = 365)
        assertEquals(
            today.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS),
            RetentionBounds.resolveResyncStartDate(prefs, today),
        )
    }

    @Test
    fun `enabled retention produces a non-null cutoff at the retention boundary`() {
        val zoneId = ZoneId.of("Europe/Berlin")
        val prefs =
            UserPreferences(
                retentionDaysEnabled = true,
                retentionDays = 180,
                scoringZoneId = zoneId.id,
            )
        val now = today.atTime(12, 0).atZone(zoneId).toInstant()
        val expected =
            today
                .minusDays(180)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        assertEquals(expected, RetentionBounds.resolveRetentionCutoffMs(prefs, now))
    }

    @Test
    fun `disabled retention produces a null cutoff (keep everything)`() {
        val prefs = UserPreferences(retentionDaysEnabled = false, retentionDays = 365)
        assertNull(RetentionBounds.resolveRetentionCutoffMs(prefs, Instant.parse("2026-06-05T12:00:00Z")))
    }

    @Test
    fun `historical window binds dates and instant to stored scoring zone`() {
        val scoringZone = ZoneId.of("Pacific/Kiritimati")
        val prefs =
            UserPreferences(
                retentionDaysEnabled = true,
                retentionDays = 30,
                scoringZoneId = scoringZone.id,
            )

        val window =
            RetentionBounds.resolveHistoricalWindow(
                prefs,
                Instant.parse("2026-08-29T10:30:00Z"),
            )

        assertEquals(LocalDate.of(2026, 8, 30), window.endDate)
        assertEquals(LocalDate.of(2026, 7, 31), window.startDate)
        assertEquals(scoringZone, window.zoneId)
        assertEquals(
            window.startDate.atStartOfDay(scoringZone).toInstant().toEpochMilli(),
            window.startTimeMs,
        )
        assertEquals(
            window.startTimeMs,
            RetentionBounds.resolveRetentionCutoffMs(
                prefs,
                Instant.parse("2026-08-29T10:30:00Z"),
            ),
        )
    }
}
