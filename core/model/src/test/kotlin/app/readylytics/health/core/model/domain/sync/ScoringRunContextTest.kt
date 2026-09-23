package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.RetentionBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals

class ScoringRunContextTest {
    @Test
    fun `capture binds instant to stored scoring zone and retention`() {
        val instant = Instant.parse("2026-03-29T22:30:00Z")
        val prefs =
            UserPreferences(
                scoringZoneId = "Europe/Berlin",
                retentionDaysEnabled = true,
                retentionDays = 180,
            )

        val context = ScoringRunContext.capture(prefs, instant)

        assertEquals(instant, context.instant)
        assertEquals(ZoneId.of("Europe/Berlin"), context.zoneId)
        assertEquals(LocalDate.of(2026, 3, 30), context.today)
        assertEquals(
            LocalDate.of(2025, 10, 1).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),
            context.retentionStartMs,
        )
    }

    @Test
    fun `disabled cleanup still has bounded import horizon`() {
        val context =
            ScoringRunContext.capture(
                UserPreferences(
                    scoringZoneId = "Pacific/Kiritimati",
                    retentionDaysEnabled = false,
                ),
                Instant.parse("2026-08-31T12:00:00Z"),
            )

        assertEquals(LocalDate.of(2026, 9, 1), context.today)
        assertEquals(context.today.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS), context.startDate)
    }
}
