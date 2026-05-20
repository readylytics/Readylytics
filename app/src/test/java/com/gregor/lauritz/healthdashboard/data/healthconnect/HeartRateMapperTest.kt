package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import io.mockk.every
import io.mockk.mockk
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

    // --- mapRestingToEntities ---

    @Test
    fun `mapRestingToEntities classifies record during sleep as SLEEP`() {
        val ts = Instant.parse("2026-05-10T02:00:00Z")
        val record =
            mockk<RestingHeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "r1"
                every { time } returns ts
                every { beatsPerMinute } returns 55L
            }

        val result = HeartRateMapper.mapRestingToEntities(listOf(record), listOf(sleepSession))

        assertEquals(1, result.size)
        assertEquals("SLEEP", result[0].recordType)
        assertEquals("sleep_1", result[0].sessionId)
        assertEquals("RESTING_r1_${ts.toEpochMilli()}", result[0].id)
        assertEquals(55, result[0].beatsPerMinute)
    }

    @Test
    fun `mapRestingToEntities classifies record outside sleep as RESTING`() {
        val ts = Instant.parse("2026-05-09T12:00:00Z")
        val record =
            mockk<RestingHeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "r2"
                every { time } returns ts
                every { beatsPerMinute } returns 60L
            }

        val result = HeartRateMapper.mapRestingToEntities(listOf(record), listOf(sleepSession))

        assertEquals(1, result.size)
        assertEquals("RESTING", result[0].recordType)
        assertNull(result[0].sessionId)
    }

    @Test
    fun `mapRestingToEntities returns empty list for empty input`() {
        val result = HeartRateMapper.mapRestingToEntities(emptyList(), listOf(sleepSession))
        assertEquals(0, result.size)
    }

    // --- mapToEntities ---

    @Test
    fun `mapToEntities handles empty samples list gracefully`() {
        val record =
            mockk<HeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "rec_empty"
                every { samples } returns emptyList()
            }

        val result = HeartRateMapper.mapToEntities(listOf(record), emptyList(), emptyList())

        assertEquals(0, result.size)
    }

    @Test
    fun `mapToEntities handles out-of-order records so both samples classified as SLEEP`() {
        // Samsung delivers records out of chronological order.
        // sample2Time is earlier than sample1Time, but record with sample1 arrives first.
        val sample1Time = Instant.parse("2026-05-10T03:00:00Z")
        val sample2Time = Instant.parse("2026-05-10T01:00:00Z")

        val sample1 =
            mockk<HeartRateRecord.Sample> {
                every { time } returns sample1Time
                every { beatsPerMinute } returns 60L
            }
        val sample2 =
            mockk<HeartRateRecord.Sample> {
                every { time } returns sample2Time
                every { beatsPerMinute } returns 65L
            }
        val recordA =
            mockk<HeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "recA"
                every { samples } returns listOf(sample1)
            }
        val recordB =
            mockk<HeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "recB"
                every { samples } returns listOf(sample2)
            }

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
        val s1 =
            mockk<HeartRateRecord.Sample> {
                every { time } returns t1
                every { beatsPerMinute } returns 60L
            }
        val s2 =
            mockk<HeartRateRecord.Sample> {
                every { time } returns t2
                every { beatsPerMinute } returns 62L
            }
        val record =
            mockk<HeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "rec_1"
                every { samples } returns listOf(s1, s2)
            }

        val result = HeartRateMapper.mapToEntities(listOf(record), emptyList(), emptyList())

        assertEquals(2, result.size)
        val ids = result.map { it.id }.toSet()
        assertEquals(setOf("rec_1_${t1.toEpochMilli()}", "rec_1_${t2.toEpochMilli()}"), ids)
    }

    @Test
    fun `mapToEntities classifies sample outside any session as RESTING`() {
        val ts = Instant.parse("2026-05-09T14:00:00Z")
        val sample =
            mockk<HeartRateRecord.Sample> {
                every { time } returns ts
                every { beatsPerMinute } returns 72L
            }
        val record =
            mockk<HeartRateRecord>(relaxed = true) {
                every { metadata.id } returns "rec_r"
                every { samples } returns listOf(sample)
            }

        val result = HeartRateMapper.mapToEntities(listOf(record), listOf(sleepSession), emptyList())

        assertEquals(1, result.size)
        assertEquals("RESTING", result[0].recordType)
        assertNull(result[0].sessionId)
    }
}
