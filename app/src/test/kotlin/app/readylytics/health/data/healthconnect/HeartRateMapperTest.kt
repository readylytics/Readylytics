package app.readylytics.health.data.healthconnect

import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HeartRateMapperTest {
    private val sleepStartMs = Instant.parse("2026-05-09T22:00:00Z").toEpochMilli()
    private val sleepEndMs = Instant.parse("2026-05-10T06:00:00Z").toEpochMilli()
    private val sleepSession =
        SleepSessionEntity(
            id = "sleep_1",
            startTime = sleepStartMs,
            endTime = sleepEndMs,
            durationMinutes = 480,
            efficiency = 0.9f,
            deepSleepMinutes = 90,
            remSleepMinutes = 120,
            lightSleepMinutes = 240,
            awakeMinutes = 30,
        )

    // --- mapToEntities ---

    @Test
    fun `mapToEntities handles empty samples list gracefully`() {
        val record =
            DomainHeartRateRecord(
                id = "rec_empty",
                deviceName = "Watch",
                samples = emptyList(),
            )

        val result = HeartRateMapper.mapToEntities(listOf(record), emptyList(), emptyList())

        assertEquals(0, result.size)
    }

    @Test
    fun `mapToEntities handles out-of-order records so both samples classified as SLEEP`() {
        // Samsung delivers records out of chronological order.
        // sample2Time is earlier than sample1Time, but record with sample1 arrives first.
        val sample1Time = Instant.parse("2026-05-10T03:00:00Z")
        val sample2Time = Instant.parse("2026-05-10T01:00:00Z")

        val sample1 = DomainHeartRateSample(time = sample1Time, beatsPerMinute = 60)
        val sample2 = DomainHeartRateSample(time = sample2Time, beatsPerMinute = 65)
        val recordA =
            DomainHeartRateRecord(
                id = "recA",
                deviceName = "Watch",
                samples = listOf(sample1),
            )
        val recordB =
            DomainHeartRateRecord(
                id = "recB",
                deviceName = "Watch",
                samples = listOf(sample2),
            )

        val result = HeartRateMapper.mapToEntities(listOf(recordA, recordB), listOf(sleepSession), emptyList())

        assertEquals(2, result.size)
        val byTs = result.associateBy { it.timestampMs }
        assertEquals("SLEEP", byTs[sample1Time.toEpochMilli()]?.recordType)
        assertEquals("SLEEP", byTs[sample2Time.toEpochMilli()]?.recordType)
    }

    @Test
    fun `mapToEntities generates unique IDs per sample using record id and timestamp`() {
        val t1 = Instant.parse("2026-05-10T02:00:00Z")
        val t2 = Instant.parse("2026-05-10T02:01:00Z")
        val s1 = DomainHeartRateSample(time = t1, beatsPerMinute = 60)
        val s2 = DomainHeartRateSample(time = t2, beatsPerMinute = 62)
        val record =
            DomainHeartRateRecord(
                id = "rec_1",
                deviceName = "Watch",
                samples = listOf(s1, s2),
            )

        val result = HeartRateMapper.mapToEntities(listOf(record), emptyList(), emptyList())

        assertEquals(2, result.size)
        val ids = result.map { it.id }.toSet()
        assertEquals(setOf("rec_1_${t1.toEpochMilli()}", "rec_1_${t2.toEpochMilli()}"), ids)
    }

    @Test
    fun `mapToEntities classifies sample outside any session as RESTING`() {
        val ts = Instant.parse("2026-05-09T14:00:00Z")
        val sample = DomainHeartRateSample(time = ts, beatsPerMinute = 72)
        val record =
            DomainHeartRateRecord(
                id = "rec_r",
                deviceName = "Watch",
                samples = listOf(sample),
            )

        val result = HeartRateMapper.mapToEntities(listOf(record), listOf(sleepSession), emptyList())

        assertEquals(1, result.size)
        assertEquals("RESTING", result[0].recordType)
        assertNull(result[0].sessionId)
    }
}
