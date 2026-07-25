package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class VitalsRecordMappersTest {
    @Test
    fun `WeightRecordMapper roundtrips through entity`() {
        val entity = WeightRecordEntity(id = "w1", timestampMs = 1_000L, weightKg = 70.5f, deviceName = "Scale")
        val domain = WeightRecordMapper.toDomain(entity)
        assertEquals("w1", domain.id)
        assertEquals(Instant.ofEpochMilli(1_000L), domain.time)
        assertEquals(70.5f, domain.weightKg)
        assertEquals("Scale", domain.deviceName)
        assertEquals(entity, WeightRecordMapper.toEntity(domain))
    }

    @Test
    fun `BloodPressureRecordMapper roundtrips through entity`() {
        val entity =
            BloodPressureRecordEntity(
                id = "bp1",
                timestampMs = 2_000L,
                systolicMmHg = 120,
                diastolicMmHg = 80,
                deviceName = "Cuff",
            )
        val domain = BloodPressureRecordMapper.toDomain(entity)
        assertEquals("bp1", domain.id)
        assertEquals(Instant.ofEpochMilli(2_000L), domain.time)
        assertEquals(120, domain.systolicMmHg)
        assertEquals(80, domain.diastolicMmHg)
        assertEquals(entity, BloodPressureRecordMapper.toEntity(domain))
    }

    @Test
    fun `BodyFatRecordMapper roundtrips through entity`() {
        val entity = BodyFatRecordEntity(id = "bf1", timestampMs = 3_000L, bodyFatPercent = 18.2f, deviceName = null)
        val domain = BodyFatRecordMapper.toDomain(entity)
        assertEquals("bf1", domain.id)
        assertEquals(Instant.ofEpochMilli(3_000L), domain.time)
        assertEquals(18.2f, domain.bodyFatPercent)
        assertEquals(null, domain.deviceName)
        assertEquals(entity, BodyFatRecordMapper.toEntity(domain))
    }
}
