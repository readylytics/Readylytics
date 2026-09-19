package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.scoring.domain.util.percentile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BpmHistogramTest {
    @Test
    fun `encode round-trips through decode`() {
        val histogram = BpmHistogram(mapOf(60 to 1, 65 to 2, 70 to 1))
        val encoded = histogram.encode()
        assertEquals("v1:60:1,65:2,70:1", encoded)
        assertEquals(histogram, BpmHistogram.decode(encoded))
    }

    @Test
    fun `empty histogram encodes to the version marker and reports no statistics`() {
        val histogram = BpmHistogram(emptyMap())
        assertEquals("v1:", histogram.encode())
        assertEquals(histogram, BpmHistogram.decode("v1:"))
        assertEquals(0, histogram.count)
        assertEquals(0L, histogram.sum)
        assertEquals(0.0, histogram.average, 0.001)
        assertNull(histogram.min)
        assertNull(histogram.max)
        assertNull(histogram.percentile(50))
    }

    @Test
    fun `count sum average min and max derive from the bins`() {
        // Expanded distribution: [60, 65, 65, 70]
        val histogram = BpmHistogram(mapOf(60 to 1, 65 to 2, 70 to 1))
        assertEquals(4, histogram.count)
        assertEquals(260L, histogram.sum)
        assertEquals(65.0, histogram.average, 0.001)
        assertEquals(60, histogram.min)
        assertEquals(70, histogram.max)
    }

    @Test
    fun `percentile is null outside the 0 to 100 domain`() {
        val histogram = BpmHistogram(mapOf(60 to 1))
        assertNull(histogram.percentile(-1))
        assertNull(histogram.percentile(101))
    }

    // I4: the histogram percentile must reproduce the warm-tier convention the rollup already
    // persists in `p5Bpm..p95Bpm` -- `MinuteBucketAggregator` computes those with
    // `List<Int>.percentile` (R-type-7 linear interpolation over the expanded sample list). A
    // nearest-rank histogram percentile would silently shift a scoring input once readers move to
    // the histogram, so equivalence is asserted over several shapes, including one where
    // nearest-rank and R-7 genuinely disagree.
    @Test
    fun `percentile equals the expanded-list percentile for every distribution shape`() {
        val distributions =
            listOf(
                (1..10).associateWith { 1 },
                mapOf(60 to 1, 65 to 2, 70 to 1),
                // R-7 interpolates to 55 here; nearest-rank would answer 100 at p50.
                mapOf(10 to 2, 100 to 2),
                mapOf(42 to 1),
                mapOf(50 to 10, 60 to 20, 70 to 15, 80 to 5),
                (50..61).associateWith { 1 },
            )
        val percentiles = listOf(0, 5, 25, 50, 75, 95, 100)

        distributions.forEach { bins ->
            val expanded = bins.entries.sortedBy { it.key }.flatMap { (bpm, n) -> List(n) { bpm } }
            val histogram = BpmHistogram(bins)
            percentiles.forEach { p ->
                assertEquals(
                    "bins=$bins p=$p",
                    expanded.percentile(p / 100.0),
                    histogram.percentile(p),
                )
            }
        }
    }

    @Test
    fun `percentile interpolates between neighbouring bins instead of picking the nearest rank`() {
        // Locks in the specific divergence from the previous nearest-rank implementation.
        assertEquals(55, BpmHistogram(mapOf(10 to 2, 100 to 2)).percentile(50))
    }

    @Test
    fun `percentiles match the twelve-sample rollup fixture`() {
        // Same 50..61 fixture as MinuteBucketAggregatorTest / DataRollupManagerTest.
        val histogram = BpmHistogram((50..61).associateWith { 1 })
        assertEquals(51, histogram.percentile(5))
        assertEquals(53, histogram.percentile(25))
        assertEquals(56, histogram.percentile(50))
        assertEquals(58, histogram.percentile(75))
        assertEquals(60, histogram.percentile(95))
    }

    @Test
    fun `decode raises a typed error for malformed payloads`() {
        listOf(
            "",
            "v2:60:1",
            "60:1",
            "v1:60",
            "v1:60:1:2",
            "v1:abc:1",
            "v1:60:abc",
            "v1:0:1",
            "v1:301:1",
            "v1:60:-1",
        ).forEach { payload ->
            assertThrows(payload, BpmHistogramFormatException::class.java) {
                BpmHistogram.decode(payload)
            }
        }
    }
}
