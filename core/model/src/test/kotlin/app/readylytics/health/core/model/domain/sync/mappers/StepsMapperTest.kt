package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepsMapperTest {
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun stepsRecord(
        start: String,
        count: Long,
        deviceName: String = "Watch",
        end: String? = null,
        id: String = start,
    ): DomainStepsRecord =
        DomainStepsRecord(
            id = id,
            startTime = Instant.parse(start),
            endTime = end?.let { Instant.parse(it) } ?: Instant.parse(start).plusSeconds(60),
            count = count,
            deviceName = deviceName,
        )

    @Test
    fun `toStepEntries extracts device label, time, interval, and count`() {
        val records =
            listOf(
                stepsRecord(
                    start = "2026-05-10T08:00:00Z",
                    count = 1200,
                    deviceName = "Garmin Forerunner",
                    end = "2026-05-10T08:15:00Z"
                ),
            )

        val entries = StepsMapper.toStepEntries(records)

        assertEquals(1, entries.size)
        assertEquals("Garmin Forerunner", entries[0].deviceName)
        assertEquals(1200L, entries[0].count)
        assertEquals(Instant.parse("2026-05-10T08:00:00Z").toEpochMilli(), entries[0].startTimeMs)
        assertEquals(Instant.parse("2026-05-10T08:15:00Z").toEpochMilli(), entries[0].endTimeMs)
    }

    @Test
    fun `phone device with no manufacturer is labelled This Phone`() {
        val records = listOf(stepsRecord("2026-05-10T08:00:00Z", 500, deviceName = "This Phone"))

        val entries = StepsMapper.toStepEntries(records)

        assertEquals("This Phone", entries[0].deviceName)
    }

    @Test
    fun `sumByDay aggregates counts per calendar day`() {
        val entries =
            listOf(
                StepsMapper.StepEntry(
                    "1",
                    Instant.parse("2026-05-10T08:00:00Z").toEpochMilli(),
                    Instant.parse("2026-05-10T08:01:00Z").toEpochMilli(),
                    "Watch",
                    1000
                ),
                StepsMapper.StepEntry(
                    "2",
                    Instant.parse("2026-05-10T20:00:00Z").toEpochMilli(),
                    Instant.parse("2026-05-10T20:01:00Z").toEpochMilli(),
                    "Watch",
                    500
                ),
                StepsMapper.StepEntry(
                    "3",
                    Instant.parse("2026-05-11T09:00:00Z").toEpochMilli(),
                    Instant.parse("2026-05-11T09:01:00Z").toEpochMilli(),
                    "Watch",
                    300
                ),
            )

        val byDay = StepsMapper.sumByDay(entries, utc)

        assertEquals(1500L, byDay[LocalDate.of(2026, 5, 10)])
        assertEquals(300L, byDay[LocalDate.of(2026, 5, 11)])
    }

    @Test
    fun `empty records produce empty results`() {
        assertEquals(emptyList<StepsMapper.StepEntry>(), StepsMapper.toStepEntries(emptyList()))
        assertEquals(emptyMap<Any, Long>(), StepsMapper.sumByDay(emptyList(), utc))
    }

    @Test
    fun `selected steps preserve the interval and conserve count`() {
        // Zero-duration
        val zeroDuration = stepsRecord(
            start = "2026-06-01T12:00:00+02:00",
            count = 10,
            end = "2026-06-01T12:00:00+02:00",
            id = "z1"
        )
        // Exact midnight start
        val exactMidnight = stepsRecord(
            start = "2026-06-02T00:00:00+02:00",
            count = 20,
            end = "2026-06-02T01:00:00+02:00",
            id = "m1"
        )
        // Multi-day interval (starts late 3rd, ends early 4th in Berlin) - attributes to start day
        val multiDay = stepsRecord(
            start = "2026-06-03T23:50:00+02:00",
            count = 30,
            end = "2026-06-04T00:10:00+02:00",
            id = "md1"
        )

        // 23h DST start in Berlin (2026-03-29) - 02:00 jumps to 03:00
        val dst23h = stepsRecord(
            start = "2026-03-29T01:30:00+01:00",
            count = 40,
            end = "2026-03-29T03:30:00+02:00",
            id = "dst1"
        )

        // 25h DST end in Berlin (2026-10-25) - 03:00 jumps back to 02:00
        val dst25h = stepsRecord(
            start = "2026-10-25T01:30:00+02:00",
            count = 50,
            end = "2026-10-25T03:30:00+01:00",
            id = "dst2"
        )

        val records = listOf(zeroDuration, exactMidnight, multiDay, dst23h, dst25h)
        val entries = StepsMapper.toStepEntries(records)

        // Ensure interval is preserved in toStepEntries
        assertEquals(Instant.parse("2026-06-01T12:00:00+02:00").toEpochMilli(), entries[0].endTimeMs)
        assertEquals(Instant.parse("2026-06-03T23:50:00+02:00").toEpochMilli(), entries[2].startTimeMs)
        assertEquals(Instant.parse("2026-06-04T00:10:00+02:00").toEpochMilli(), entries[2].endTimeMs)

        val byDay = StepsMapper.sumByDay(entries, berlin)

        // Zero duration attributed correctly
        assertEquals(10L, byDay[LocalDate.of(2026, 6, 1)])
        // Exact midnight
        assertEquals(20L, byDay[LocalDate.of(2026, 6, 2)])
        // Multi-day attributed to start day
        assertEquals(30L, byDay[LocalDate.of(2026, 6, 3)])
        // DST days
        assertEquals(40L, byDay[LocalDate.of(2026, 3, 29)])
        assertEquals(50L, byDay[LocalDate.of(2026, 10, 25)])

        // Ensure duplicate IDs are not counted twice
        val entriesWithDup = entries + entries[2].copy()
        val byDayWithDup = StepsMapper.sumByDay(entriesWithDup, berlin)
        assertEquals(30L, byDayWithDup[LocalDate.of(2026, 6, 3)])
    }
}
