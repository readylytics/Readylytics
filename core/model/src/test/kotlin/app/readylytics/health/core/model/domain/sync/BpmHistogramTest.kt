package app.readylytics.health.core.model.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BpmHistogramTest {
    @Test
    fun testEncodeDecode() {
        val histogram = BpmHistogram(mapOf(60 to 1, 65 to 2, 70 to 1))
        val encoded = histogram.encode()
        assertEquals("v1:60:1,65:2,70:1", encoded)
        
        val decoded = BpmHistogram.decode(encoded)
        assertEquals(histogram, decoded)
    }

    @Test
    fun testEmpty() {
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
    fun testStats() {
        // [60, 65, 65, 70]
        val histogram = BpmHistogram(mapOf(60 to 1, 65 to 2, 70 to 1))
        assertEquals(4, histogram.count)
        assertEquals(260L, histogram.sum)
        assertEquals(65.0, histogram.average, 0.001)
        assertEquals(60, histogram.min)
        assertEquals(70, histogram.max)
    }

    @Test
    fun testPercentiles() {
        // 10 samples: 1..10
        val bins = (1..10).associateWith { 1 }
        val histogram = BpmHistogram(bins)
        
        assertEquals(1, histogram.percentile(5))
        assertEquals(3, histogram.percentile(25))
        assertEquals(6, histogram.percentile(50))
        assertEquals(8, histogram.percentile(75))
        assertEquals(10, histogram.percentile(95))
    }
}
