package app.readylytics.health.data.healthconnect

import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.sync.mappers.StepsMapper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StepsMapperTest {
    private val utc = ZoneId.of("UTC")

    private fun stepsRecord(
        start: String,
        count: Long,
        deviceName: String = "Watch",
    ): DomainStepsRecord =
        DomainStepsRecord(
            id = start,
            startTime = Instant.parse(start),
            endTime = Instant.parse(start).plusSeconds(60),
            count = count,
            deviceName = deviceName,
        )

    @Test
    fun `toStepEntries extracts device label, time and count`() {
        val records =
            listOf(
                stepsRecord("2026-05-10T08:00:00Z", 1200, deviceName = "Garmin Forerunner"),
            )

        val entries = StepsMapper.toStepEntries(records)

        assertEquals(1, entries.size)
        assertEquals("Garmin Forerunner", entries[0].deviceName)
        assertEquals(1200L, entries[0].count)
        assertEquals(Instant.parse("2026-05-10T08:00:00Z").toEpochMilli(), entries[0].startTimeMs)
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
                StepsMapper.StepEntry(Instant.parse("2026-05-10T08:00:00Z").toEpochMilli(), "Watch", 1000),
                StepsMapper.StepEntry(Instant.parse("2026-05-10T20:00:00Z").toEpochMilli(), "Watch", 500),
                StepsMapper.StepEntry(Instant.parse("2026-05-11T09:00:00Z").toEpochMilli(), "Watch", 300),
            )

        val byDay = StepsMapper.sumByDay(entries, utc)

        assertEquals(1500L, byDay[Instant.parse("2026-05-10T00:00:00Z").atZone(utc).toLocalDate()])
        assertEquals(300L, byDay[Instant.parse("2026-05-11T00:00:00Z").atZone(utc).toLocalDate()])
    }

    @Test
    fun `empty records produce empty results`() {
        assertEquals(emptyList<StepsMapper.StepEntry>(), StepsMapper.toStepEntries(emptyList()))
        assertEquals(emptyMap<Any, Long>(), StepsMapper.sumByDay(emptyList(), utc))
    }
}
