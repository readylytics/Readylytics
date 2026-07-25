package app.readylytics.health.domain.sync

import app.readylytics.health.domain.preferences.UserPreferences
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

class FullHistoricalResyncUseCaseTest {
    @Test
    fun `resolveScoringToday uses stored scoring timezone`() {
        val instant = Instant.parse("2026-07-20T00:30:00Z")

        assertEquals(
            LocalDate.of(2026, 7, 20),
            resolveScoringToday(UserPreferences(scoringZoneId = "Europe/Berlin"), instant),
        )
        assertEquals(
            LocalDate.of(2026, 7, 19),
            resolveScoringToday(UserPreferences(scoringZoneId = "America/Los_Angeles"), instant),
        )
    }
}
