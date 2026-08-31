package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

// R2-DB-004. Percentile expectations below are hand-verified against MathUtils.percentile's
// linear-interpolation formula (index = p * (size - 1), interpolate between floor/ceil, round
// half up) -- NOT copied from the Phase-1 plan document as-is. For the 12-sample fixture
// (sorted [50..61], n=12, indices 0..11):
//   p5:  index = 0.05*11 = 0.55  -> lower=0 (50), upper=1 (51), fraction=0.55
//        -> 50 + (51-50)*0.55 = 50.55 -> rounds to 51
//   p50: index = 0.50*11 = 5.5   -> lower=5 (55), upper=6 (56), fraction=0.5
//        -> 55 + (56-55)*0.5 = 55.5 -> rounds to 56
//   p95: index = 0.95*11 = 10.45 -> lower=10 (60), upper=11 (61), fraction=0.45
//        -> 60 + (61-60)*0.45 = 60.45 -> rounds to 60
// (the plan document's worked example asserted p95Bpm=61; that arithmetic is wrong -- 60.45
// rounds down to 60, not up to 61 -- so this test uses the corrected value.)
class MinuteBucketAggregatorTest {
    @Test
    fun `aggregates one minute of samples into min max avg count and percentiles`() {
        val samples =
            (0 until 12).map { i ->
                HeartRateRecordEntity(
                    sourceRecordRef = 1L,
                    timestampMs = i * 5_000L, // 12 samples across minute 0
                    beatsPerMinute = 50 + i, // 50..61
                    recordType = "SLEEP",
                    sessionId = "s1",
                )
            }
        val buckets = samples.aggregateIntoMinuteBuckets()
        assertEquals(1, buckets.size)
        val bucket = buckets.single()
        assertEquals(0L, bucket.bucketStartMs)
        assertEquals(50, bucket.minBpm)
        assertEquals(61, bucket.maxBpm)
        assertEquals(12, bucket.sampleCount)
        assertEquals(55.5, bucket.avgBpm, 0.01)
        assertEquals(51, bucket.p5Bpm)
        assertEquals(56, bucket.p50Bpm)
        assertEquals(60, bucket.p95Bpm)
    }

    @Test
    fun `splits samples spanning two minutes into two buckets`() {
        val samples =
            listOf(
                HeartRateRecordEntity(1L, 0L, 60, "SLEEP", "s1"),
                HeartRateRecordEntity(1L, 60_000L, 65, "SLEEP", "s1"),
            )
        val buckets = samples.aggregateIntoMinuteBuckets()
        assertEquals(2, buckets.size)
    }
}
