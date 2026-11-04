package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.repository.SleepSessionData
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CircadianWakeBaselineTest {
    private val zone = ZoneId.of("UTC")

    private fun session(startIso: String, endIso: String): SleepSessionData {
        val start = Instant.parse(startIso).toEpochMilli()
        val end = Instant.parse(endIso).toEpochMilli()
        return SleepSessionData(
            id = "s_$start",
            startTime = start,
            endTime = end,
            durationMinutes = ((end - start) / 60_000L).toInt(),
            efficiency = 90f,
            deepSleepMinutes = 60,
            remSleepMinutes = 60,
            lightSleepMinutes = 120,
            awakeMinutes = 10,
            deviceName = "Watch",
        )
    }

    @Test
    fun `resolveFromQualifying accurately calculates median wake straddling noon`() {
        val sessions = listOf(
            session("2026-06-01T04:00:00Z", "2026-06-01T11:50:00Z"),
            session("2026-06-02T04:00:00Z", "2026-06-02T11:55:00Z"),
            session("2026-06-03T04:00:00Z", "2026-06-03T12:05:00Z"),
            session("2026-06-04T04:00:00Z", "2026-06-04T12:10:00Z"),
        )

        val baseline = CircadianWakeBaseline.resolve(sessions, baselineCount = 4, zone = zone)
        assertNotNull(baseline)
        // Median wake time across noon must be 12:00 (720 min), not midnight
        assertEquals(720, baseline.medianWakeMinutes)
    }

    @Test
    fun `resolveFromQualifying computes median bedtime for daytime sleeper straddling noon`() {
        val sessions = listOf(
            session("2026-06-01T11:30:00Z", "2026-06-01T19:30:00Z"),
            session("2026-06-02T12:30:00Z", "2026-06-02T20:30:00Z"),
            session("2026-06-03T13:00:00Z", "2026-06-03T21:00:00Z"),
        )

        val baseline = CircadianWakeBaseline.resolve(sessions, baselineCount = 3, zone = zone)
        assertNotNull(baseline)
        // 11:30, 12:30, 13:00 -> 12:30 (750 min); a linear noon pivot would yield 13:00.
        assertEquals(750, baseline.medianBedtimeMinutes)
    }

    @Test
    fun `resolveFromQualifying keeps past-midnight bedtimes in clock minutes`() {
        val sessions = listOf(
            session("2026-06-02T23:50:00Z", "2026-06-03T07:30:00Z"),
            session("2026-06-04T00:10:00Z", "2026-06-04T07:30:00Z"),
            session("2026-06-05T00:30:00Z", "2026-06-05T07:30:00Z"),
        )

        val baseline = CircadianWakeBaseline.resolve(sessions, baselineCount = 3, zone = zone)
        assertNotNull(baseline)
        assertEquals(10, baseline.medianBedtimeMinutes)
    }
}
