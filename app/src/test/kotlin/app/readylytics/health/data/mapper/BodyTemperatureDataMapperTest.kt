package app.readylytics.health.data.mapper

import app.readylytics.health.domain.model.DomainBodyTemperatureRecord
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class BodyTemperatureDataMapperTest {
    @Test
    fun `toEntity builds a composite id from record id and epoch millis`() {
        val record =
            DomainBodyTemperatureRecord(
                id = "hc-123",
                time = Instant.ofEpochMilli(5_000L),
                celsius = 36.9f,
                deviceName = "Pixel Watch",
            )

        val entity = BodyTemperatureDataMapper.toEntity(record)

        assertEquals("hc-123_5000", entity.id)
        assertEquals(5_000L, entity.timestampMs)
        assertEquals(36.9f, entity.celsius)
        assertEquals("Pixel Watch", entity.deviceName)
    }

    @Test
    fun `toEntities maps every record in the list`() {
        val records =
            listOf(
                DomainBodyTemperatureRecord("a", Instant.ofEpochMilli(1L), 36.5f, "Whoop"),
                DomainBodyTemperatureRecord("b", Instant.ofEpochMilli(2L), 37.1f, "Oura"),
            )

        val entities = BodyTemperatureDataMapper.toEntities(records)

        assertEquals(2, entities.size)
        assertEquals("a_1", entities[0].id)
        assertEquals("b_2", entities[1].id)
    }
}
