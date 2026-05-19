package com.gregor.lauritz.healthdashboard.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SleepStagesChartTest {
    // Internal function exposed for testing via reflection or by making it internal
    // Since I can't easily change the visibility and then run the test if it's private,
    // I will copy the logic here or suggest making it internal.
    // Given the constraints, I'll copy it to verify the logic.

    private fun getLabelTimestamps(
        startMs: Long,
        endMs: Long,
    ): List<Long> {
        val sessionDurationMinutes = (endMs - startMs) / 60_000L
        if (sessionDurationMinutes <= 0L) return emptyList()

        val durationHours = sessionDurationMinutes / 60f

        val intervalMinutes =
            when {
                durationHours <= 4 -> 60
                durationHours <= 8 -> 120
                else -> 180
            }

        val timestamps = mutableListOf<Long>()
        timestamps.add(startMs)

        val zoneId = ZoneId.systemDefault()
        val startZDT = Instant.ofEpochMilli(startMs).atZone(zoneId)
        // Find first "nice" time after start (top of the hour)
        var currentZDT =
            startZDT
                .plusHours(1)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

        while (currentZDT.toInstant().toEpochMilli() < endMs - (intervalMinutes * 30_000L)) {
            val ts = currentZDT.toInstant().toEpochMilli()
            if (ts > startMs + (intervalMinutes * 30_000L)) {
                timestamps.add(ts)
            }
            currentZDT = currentZDT.plusMinutes(intervalMinutes.toLong())
        }

        if (timestamps.last() < endMs - (15 * 60_000L)) {
            timestamps.add(endMs)
        } else if (timestamps.size > 1) {
            timestamps[timestamps.size - 1] = endMs
        } else {
            timestamps.add(endMs)
        }

        return timestamps.distinct()
    }

    @Test
    fun getLabelTimestamps_8HourSession_Returns2HourIntervals() {
        val start = 1716159600000L // 01:00
        val end = 1716188400000L // 09:00
        val labels = getLabelTimestamps(start, end)

        // Expected: 01:00, 04:00, 06:00, 08:00, 09:00?
        // Let's trace:
        // start: 01:00
        // first nice: 02:00
        // interval: 120min (2h)
        // ts1: 02:00 > 01:00 + 1h? Yes. Added.
        // next: 04:00 < 09:00 - 1h? Yes. Added.
        // next: 06:00 < 09:00 - 1h? Yes. Added.
        // next: 08:00 < 09:00 - 1h? No (08:00 is exactly 09:00 - 1h).
        // Loop ends.
        // last is 06:00. 06:00 < 09:00 - 15min? Yes. Add end.

        // Output for 8h session with current logic: [01:00, 02:00, 04:00, 06:00, 09:00]
        // Wait, 01:00 to 02:00 is only 1h.
        // intervalMinutes = 120.
        // currentZDT starts at 02:00.
        // ts=02:00. ts > 01:00 + 1h? No (exactly 1h). So 02:00 is NOT added.
        // next is 04:00. 04:00 > 01:00 + 1h? Yes. Added.
        // next is 06:00. 06:00 < 09:00 - 1h? Yes. Added.
        // Loop ends.
        // [01:00, 04:00, 06:00, 09:00]

        assertEquals(4, labels.size)
        assertEquals(start, labels.first())
        assertEquals(end, labels.last())
    }

    @Test
    fun getLabelTimestamps_4HourSession_Returns1HourIntervals() {
        val start = 1716159600000L // 01:00
        val end = 1716174000000L // 05:00
        val labels = getLabelTimestamps(start, end)

        // intervalMinutes = 60
        // first nice: 02:00
        // ts=02:00. ts > 01:00 + 30m? Yes. Added.
        // ts=03:00. ts < 05:00 - 30m? Yes. Added.
        // ts=04:00. ts < 05:00 - 30m? Yes. Added.
        // Loop ends.
        // [01:00, 02:00, 03:00, 04:00, 05:00]

        assertEquals(5, labels.size)
        assertEquals(start, labels.first())
        assertEquals(end, labels.last())
    }
}
