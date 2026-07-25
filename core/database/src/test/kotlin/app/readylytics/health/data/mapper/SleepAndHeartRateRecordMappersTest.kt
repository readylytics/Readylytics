package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepAndHeartRateRecordMappersTest {
    @Test
    fun `HeartRateRecordMapper roundtrips through entity`() {
        val entity =
            HeartRateRecordEntity(
                id = "hr1_1000",
                timestampMs = 1_000L,
                beatsPerMinute = 60,
                recordType = "SLEEP",
                sessionId = "s1",
                deviceName = "Watch",
            )
        val domain = HeartRateRecordMapper.toDomain(entity)
        assertEquals("hr1_1000", domain.id)
        assertEquals(1_000L, domain.timestampMs)
        assertEquals(60, domain.beatsPerMinute)
        assertEquals("SLEEP", domain.recordType)
        assertEquals("s1", domain.sessionId)
        assertEquals("Watch", domain.deviceName)
        assertEquals(entity, HeartRateRecordMapper.toEntity(domain))
    }

    @Test
    fun `SleepSessionMapper roundtrips through entity`() {
        val entity =
            SleepSessionEntity(
                id = "s1",
                startTime = 1_000L,
                endTime = 2_000L,
                durationMinutes = 60,
                efficiency = 90f,
                deepSleepMinutes = 20,
                remSleepMinutes = 15,
                lightSleepMinutes = 25,
                awakeMinutes = 5,
                sleepScore = 80f,
                startZoneOffsetSeconds = 3600,
                endZoneOffsetSeconds = 3600,
                deviceName = "Watch",
            )
        val domain = SleepSessionMapper.toDomain(entity)
        assertEquals("s1", domain.id)
        assertEquals(1_000L, domain.startTime)
        assertEquals(2_000L, domain.endTime)
        assertEquals(60, domain.durationMinutes)
        assertEquals(90f, domain.efficiency)
        assertEquals(20, domain.deepSleepMinutes)
        assertEquals(15, domain.remSleepMinutes)
        assertEquals(25, domain.lightSleepMinutes)
        assertEquals(5, domain.awakeMinutes)
        assertEquals(80f, domain.sleepScore)
        assertEquals(3600, domain.startZoneOffsetSeconds)
        assertEquals(3600, domain.endZoneOffsetSeconds)
        assertEquals("Watch", domain.deviceName)
        assertEquals(entity, SleepSessionMapper.toEntity(domain))
    }
}
