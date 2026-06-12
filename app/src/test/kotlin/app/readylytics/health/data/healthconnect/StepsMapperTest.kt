package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StepsMapperTest {
    private val utc = ZoneId.of("UTC")

    private fun stepsRecord(
        start: String,
        count: Long,
        manufacturer: String? = null,
        model: String? = null,
        deviceType: Int = Device.TYPE_UNKNOWN,
        packageName: String = "com.example.app",
    ): StepsRecord =
        mockk(relaxed = true) {
            every { startTime } returns Instant.parse(start)
            every { this@mockk.count } returns count
            every { metadata.device } returns Device(manufacturer = manufacturer, model = model, type = deviceType)
            every { metadata.dataOrigin } returns DataOrigin(packageName)
        }

    @Test
    fun `toStepEntries extracts device label, time and count`() {
        val records =
            listOf(
                stepsRecord("2026-05-10T08:00:00Z", 1200, manufacturer = "Garmin", model = "Forerunner"),
            )

        val entries = StepsMapper.toStepEntries(records)

        assertEquals(1, entries.size)
        assertEquals("Garmin Forerunner", entries[0].deviceName)
        assertEquals(1200L, entries[0].count)
        assertEquals(Instant.parse("2026-05-10T08:00:00Z").toEpochMilli(), entries[0].startTimeMs)
    }

    @Test
    fun `phone device with no manufacturer is labelled This Phone`() {
        val records = listOf(stepsRecord("2026-05-10T08:00:00Z", 500, deviceType = Device.TYPE_PHONE))

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
