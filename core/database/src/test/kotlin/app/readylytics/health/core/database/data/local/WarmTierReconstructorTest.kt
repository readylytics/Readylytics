package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmTierReconstructorTest {
    @Test
    fun `timestamped samples holder behaves correctly`() {
        val timestamps = longArrayOf(1000L, 2000L, 3000L)
        val bpms = intArrayOf(60, 70, 80)
        val samples = TimestampedSamples(timestamps, bpms)

        assertEquals(3, samples.size)
        assertFalse(samples.isEmpty)

        val iteratedTimestamps = mutableListOf<Long>()
        val iteratedBpms = mutableListOf<Int>()
        val iteratedIndices = mutableListOf<Int>()

        samples.forEachIndexed { index, ts, bpm ->
            iteratedIndices.add(index)
            iteratedTimestamps.add(ts)
            iteratedBpms.add(bpm)
        }

        assertEquals(listOf(0, 1, 2), iteratedIndices)
        assertEquals(listOf(1000L, 2000L, 3000L), iteratedTimestamps)
        assertEquals(listOf(60, 70, 80), iteratedBpms)

        val emptySamples = TimestampedSamples(LongArray(0), IntArray(0))
        assertEquals(0, emptySamples.size)
        assertTrue(emptySamples.isEmpty)

        val sameSamples = TimestampedSamples(longArrayOf(1000L, 2000L, 3000L), intArrayOf(60, 70, 80))
        assertEquals(samples, sameSamples)
        assertEquals(samples.hashCode(), sameSamples.hashCode())
    }

    @Test
    fun `empty bucket list reconstructs empty primitive arrays`() {
        val emptyList = emptyList<HrMinuteBucketEntity>()
        val sampleValues = emptyList.reconstructSampleValues()
        assertEquals(0, sampleValues.size)

        val timestampedSamples = emptyList.reconstructTimestampedSamples()
        assertEquals(0, timestampedSamples.size)
        assertTrue(timestampedSamples.isEmpty)
    }

    @Test
    fun `flat mean reconstruction for bucket with fewer than 3 samples`() {
        val bucket = HrMinuteBucketEntity(
            bucketStartMs = 60_000L,
            bucketEndMs = 119_999L,
            sampleCount = 2,
            minBpm = 60,
            maxBpm = 64,
            avgBpm = 62.4,
            recordType = "SLEEP",
            p25Bpm = null,
            p50Bpm = null,
            p75Bpm = null,
            p95Bpm = null,
        )

        val values = listOf(bucket).reconstructSampleValues()
        assertArrayEquals(intArrayOf(62, 62), values)

        val timestamped = listOf(bucket).reconstructTimestampedSamples()
        assertEquals(2, timestamped.size)
        assertArrayEquals(longArrayOf(60_000L, 90_000L), timestamped.timestampsMs)
        assertArrayEquals(intArrayOf(62, 62), timestamped.bpmValues)
    }

    @Test
    fun `three point reconstruction for legacy bucket with 3 or more samples`() {
        val bucket = HrMinuteBucketEntity(
            bucketStartMs = 60_000L,
            bucketEndMs = 119_999L,
            sampleCount = 5,
            minBpm = 50,
            maxBpm = 80,
            avgBpm = 65.0,
            recordType = "SLEEP",
        )

        val values = listOf(bucket).reconstructSampleValues()
        assertArrayEquals(intArrayOf(50, 65, 65, 65, 80), values)

        val timestamped = listOf(bucket).reconstructTimestampedSamples()
        assertEquals(5, timestamped.size)
        assertArrayEquals(
            longArrayOf(60_000L, 72_000L, 84_000L, 96_000L, 108_000L),
            timestamped.timestampsMs,
        )
        assertArrayEquals(intArrayOf(50, 65, 65, 65, 80), timestamped.bpmValues)
    }

    @Test
    fun `percentile sketch reconstruction interpolates quantiles correctly`() {
        val bucket = HrMinuteBucketEntity(
            bucketStartMs = 60_000L,
            bucketEndMs = 119_999L,
            sampleCount = 7,
            minBpm = 50,
            maxBpm = 100,
            avgBpm = 75.0,
            recordType = "SLEEP",
            p5Bpm = 55,
            p25Bpm = 65,
            p50Bpm = 75,
            p75Bpm = 85,
            p95Bpm = 95,
        )

        val values = listOf(bucket).reconstructSampleValues()
        assertEquals(7, values.size)
        // Quantiles for i = 0..6: (i + 0.5) / 7
        // i=0: 0.5/7 ≈ 0.0714 -> between 0.05 (55) and 0.25 (65)
        // i=3: 3.5/7 = 0.50 -> exactly p50 = 75
        // i=6: 6.5/7 ≈ 0.9286 -> between 0.75 (85) and 0.95 (95)
        assertEquals(75, values[3])
        val isMonotonic = (0 until values.size - 1).all { values[it] <= values[it + 1] }
        assertTrue("Values must be monotonic non-decreasing", isMonotonic)

        val timestamped = listOf(bucket).reconstructTimestampedSamples()
        assertEquals(7, timestamped.size)
        assertArrayEquals(values, timestamped.bpmValues)
    }

    @Test
    fun `multiple buckets are chained consecutively in output primitive arrays`() {
        val bucket1 = HrMinuteBucketEntity(
            bucketStartMs = 60_000L,
            bucketEndMs = 119_999L,
            sampleCount = 2,
            minBpm = 60,
            maxBpm = 60,
            avgBpm = 60.0,
            recordType = "SLEEP",
        )
        val bucket2 = HrMinuteBucketEntity(
            bucketStartMs = 120_000L,
            bucketEndMs = 179_999L,
            sampleCount = 3,
            minBpm = 70,
            maxBpm = 80,
            avgBpm = 75.0,
            recordType = "SLEEP",
        )

        val combinedValues = listOf(bucket1, bucket2).reconstructSampleValues()
        assertArrayEquals(intArrayOf(60, 60, 70, 75, 80), combinedValues)

        val combinedTimestamped = listOf(bucket1, bucket2).reconstructTimestampedSamples()
        assertEquals(5, combinedTimestamped.size)
        assertArrayEquals(
            longArrayOf(60_000L, 90_000L, 120_000L, 140_000L, 160_000L),
            combinedTimestamped.timestampsMs,
        )
        assertArrayEquals(intArrayOf(60, 60, 70, 75, 80), combinedTimestamped.bpmValues)
    }
}
