package app.readylytics.health.data.preferences

import app.readylytics.health.domain.util.toMidnightEpochMilli
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals

class ScoringZoneTest {
    @Test
    fun scoringZone_validId_isParsed() {
        val prefs = UserPreferences(scoringZoneId = "America/Los_Angeles")
        assertEquals(ZoneId.of("America/Los_Angeles"), prefs.scoringZone())
    }

    @Test
    fun scoringZone_blank_fallsBackToDeviceZone() {
        val prefs = UserPreferences(scoringZoneId = "")
        assertEquals(ZoneId.systemDefault(), prefs.scoringZone())
    }

    @Test
    fun scoringZone_invalidId_fallsBackToDeviceZone() {
        val prefs = UserPreferences(scoringZoneId = "Not/AZone")
        assertEquals(ZoneId.systemDefault(), prefs.scoringZone())
    }

    /**
     * The core determinism guarantee: with a stored scoring zone, the day-boundary key for a
     * given date is identical regardless of the device's ambient timezone.
     */
    @Test
    fun dayMidnight_isIndependentOfDeviceZone_whenScoringZoneStored() {
        val original = TimeZone.getDefault()
        try {
            val date = LocalDate.of(2026, 6, 13)
            val prefs = UserPreferences(scoringZoneId = "America/Los_Angeles")

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val underUtc = date.toMidnightEpochMilli(prefs.scoringZone())

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val underTokyo = date.toMidnightEpochMilli(prefs.scoringZone())

            assertEquals(underUtc, underTokyo)
            assertEquals(
                date.atStartOfDay(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli(),
                underUtc,
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
