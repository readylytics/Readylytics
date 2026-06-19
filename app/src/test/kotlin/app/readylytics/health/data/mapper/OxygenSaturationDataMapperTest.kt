package app.readylytics.health.data.mapper

import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OxygenSaturationDataMapperTest {
    @Test
    fun `toEntity maps Health Connect record to Room entity correctly`() {
        val testTime = Instant.parse("2026-06-01T04:00:00Z")
        val record =
            DomainOxygenSaturationRecord(
                id = "spo2_test_id",
                time = testTime,
                percentage = 98.5f,
                deviceName = "Google Fit",
            )

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
            DomainOxygenSaturationRecord(
                id = "id_1",
                time = time1,
                percentage = 99.0f,
                deviceName = "Garmin Connect",
            )

        val record2 =
            DomainOxygenSaturationRecord(
                id = "id_2",
                time = time2,
                percentage = 94.0f,
                deviceName = "Oura",
            )

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
