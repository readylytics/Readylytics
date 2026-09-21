package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HrvMapperTest {
    private val sleepStartMs = Instant.parse("2026-05-09T22:00:00Z").toEpochMilli()
    private val sleepEndMs = Instant.parse("2026-05-10T06:00:00Z").toEpochMilli()
    private val sleepSession =
        SleepSessionInput(
            id = "sleep_1",
            startTime = sleepStartMs,
            endTime = sleepEndMs,
            durationMinutes = 480,
            efficiency = 90.0f,
            deepSleepMinutes = 90,
            remSleepMinutes = 120,
            lightSleepMinutes = 240,
            awakeMinutes = 30,
            sleepScore = null,
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Watch",
        )

    @Test
    fun `mapToInputs handles empty records gracefully`() {
        val result = HrvMapper.mapToInputs(emptyList(), listOf(sleepSession))
        assertEquals(0, result.size)
    }

    @Test
    fun `mapToInputs sorts out-of-order records and classifies sleep samples`() {
        val t1 = Instant.parse("2026-05-10T03:00:00Z")
        val t2 = Instant.parse("2026-05-10T01:00:00Z")

        val r1 = DomainHrvRecord(id = "hrv1", time = t1, rmssdMs = 45f, deviceName = "Watch")
        val r2 = DomainHrvRecord(id = "hrv2", time = t2, rmssdMs = 50f, deviceName = "Watch")

        val result = HrvMapper.mapToInputs(listOf(r1, r2), listOf(sleepSession))

        assertEquals(2, result.size)
        val rows = result.flatMap { it.rows }
        assertEquals(2, rows.size)
        // Should be sorted by timestamp ascending
        assertEquals(t2.toEpochMilli(), rows[0].timestampMs)
        assertEquals(t1.toEpochMilli(), rows[1].timestampMs)
        assertEquals("SLEEP", rows[0].recordType)
        assertEquals("sleep_1", rows[0].sessionId)
        assertEquals("SLEEP", rows[1].recordType)
        assertEquals("sleep_1", rows[1].sessionId)
    }

    @Test
    fun `mapToInputs generates unique IDs per record using record id and timestamp`() {
        val t = Instant.parse("2026-05-10T02:00:00Z")
        val record = DomainHrvRecord(id = "hrv_rec", time = t, rmssdMs = 55f, deviceName = "Ring")

        val result = HrvMapper.mapToInputs(listOf(record), emptyList())

        assertEquals(1, result.size)
        val rows = result[0].rows
        assertEquals("hrv_rec_${t.toEpochMilli()}", rows[0].id)
        assertEquals(55f, rows[0].rmssdMs)
        assertEquals("Ring", rows[0].deviceName)
        assertEquals("RESTING", rows[0].recordType)
        assertNull(rows[0].sessionId)
    }

    @Test
    fun `mapToInputs classifies sample outside sleep as RESTING`() {
        val t = Instant.parse("2026-05-09T15:00:00Z")
        val record = DomainHrvRecord(id = "hrv_day", time = t, rmssdMs = 40f, deviceName = "Watch")

        val result = HrvMapper.mapToInputs(listOf(record), listOf(sleepSession))

        assertEquals(1, result.size)
        val rows = result[0].rows
        assertEquals("RESTING", rows[0].recordType)
        assertNull(rows[0].sessionId)
    }
}
