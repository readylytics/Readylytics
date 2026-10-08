package app.readylytics.health.data.preferences

import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserPreferencesBirthdayMappingTest {
    private val clock =
        Clock.fixed(
            Instant.parse("2026-01-01T10:30:00Z"),
            ZoneId.of("Pacific/Honolulu"),
        )

    private fun protoBirthday(
        year: Int,
        month: Int,
        day: Int,
        zone: String,
    ): UserPreferencesProto =
        UserPreferencesProto
            .newBuilder()
            .setBirthYear(year)
            .setBirthMonth(month)
            .setBirthDay(day)
            .setScoringZoneId(zone)
            .build()

    @Test
    fun scoringZone_boundaryDates_evaluatedAgainstScoringZone() {
        // Scoring Kiritimati is Jan 2 while the clock/device zone is Jan 1.
        assertEquals("2026-01-02", protoBirthday(2026, 1, 2, "Pacific/Kiritimati").toDomainModel(clock).birthDate)
        assertNull(protoBirthday(2026, 1, 3, "Pacific/Kiritimati").toDomainModel(clock).birthDate)
        assertEquals("2024-02-29", protoBirthday(2024, 2, 31, "UTC").toDomainModel(clock).birthDate)
        assertNull(protoBirthday(0, 2, 1, "UTC").toDomainModel(clock).birthDate)
    }

    @Test
    fun zeroMonthOrDay_mapsToNull() {
        assertNull(protoBirthday(1990, 0, 15, "UTC").toDomainModel(clock).birthDate)
        assertNull(protoBirthday(1990, 5, 0, "UTC").toDomainModel(clock).birthDate)
    }

    @Test
    fun monthClamp13_mapsToDecember() {
        assertEquals("1990-12-10", protoBirthday(1990, 13, 10, "UTC").toDomainModel(clock).birthDate)
    }

    @Test
    fun nonLeapFebruary_clampsTo28() {
        assertEquals("2023-02-28", protoBirthday(2023, 2, 31, "UTC").toDomainModel(clock).birthDate)
    }

    @Test
    fun blankOrInvalidScoringZone_fallsBackToDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            // When device zone is Kiritimati (UTC+14), today is 2026-01-02
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals("2026-01-02", protoBirthday(2026, 1, 2, "").toDomainModel(clock).birthDate)
            assertEquals("2026-01-02", protoBirthday(2026, 1, 2, "Invalid/Zone").toDomainModel(clock).birthDate)
            assertNull(protoBirthday(2026, 1, 3, "").toDomainModel(clock).birthDate)

            // When device zone is Honolulu (UTC-10), today is 2026-01-01
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertNull(protoBirthday(2026, 1, 2, "").toDomainModel(clock).birthDate)
            assertNull(protoBirthday(2026, 1, 2, "Invalid/Zone").toDomainModel(clock).birthDate)
            assertEquals("2026-01-01", protoBirthday(2026, 1, 1, "").toDomainModel(clock).birthDate)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
