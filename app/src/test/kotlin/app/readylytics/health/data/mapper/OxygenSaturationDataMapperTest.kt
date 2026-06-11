package app.readylytics.health.data.mapper

import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.units.Percentage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OxygenSaturationDataMapperTest {
    @Test
    fun `toEntity maps Health Connect record to Room entity correctly`() {
        val testTime = Instant.parse("2026-06-01T04:00:00Z")
        val record =
            mockk<OxygenSaturationRecord>(relaxed = true) {
                every { metadata.id } returns "spo2_test_id"
                every { time } returns testTime
                every { percentage } returns Percentage(value = 98.5)
                every { metadata.device } returns null
                every { metadata.dataOrigin.packageName } returns "com.google.android.apps.fitness"
            }

        val entity = OxygenSaturationDataMapper.toEntity(record)

        assertEquals("spo2_test_id_${testTime.toEpochMilli()}", entity.id)
        assertEquals(testTime.toEpochMilli(), entity.timestampMs)
        assertEquals(98.5f, entity.percentage)
        assertEquals("Google Fit", entity.deviceName) // mapped via DeviceLabel from package name
    }

    @Test
    fun `toEntities maps list of records correctly`() {
        val time1 = Instant.parse("2026-06-01T04:00:00Z")
        val time2 = Instant.parse("2026-06-01T05:00:00Z")

        val record1 =
            mockk<OxygenSaturationRecord>(relaxed = true) {
                every { metadata.id } returns "id_1"
                every { time } returns time1
                every { percentage } returns Percentage(value = 99.0)
                every { metadata.device } returns null
                every { metadata.dataOrigin.packageName } returns "com.garmin.android.apps.connect"
            }

        val record2 =
            mockk<OxygenSaturationRecord>(relaxed = true) {
                every { metadata.id } returns "id_2"
                every { time } returns time2
                every { percentage } returns Percentage(value = 94.0)
                every { metadata.device } returns null
                every { metadata.dataOrigin.packageName } returns "com.ouraring.ouraring"
            }

        val entities = OxygenSaturationDataMapper.toEntities(listOf(record1, record2))

        assertEquals(2, entities.size)
        assertEquals(99.0f, entities[0].percentage)
        assertEquals("id_1_${time1.toEpochMilli()}", entities[0].id)
        assertEquals("Garmin Connect", entities[0].deviceName)

        assertEquals(94.0f, entities[1].percentage)
        assertEquals("id_2_${time2.toEpochMilli()}", entities[1].id)
        assertEquals("Oura", entities[1].deviceName)
    }
}
