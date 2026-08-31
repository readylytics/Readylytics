package app.readylytics.health.core.scoring.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class MathUtilsTest {
    @Test
    fun `mean of empty list is 0`() {
        assertEquals(0f, emptyList<Float>().mean())
    }

    @Test
    fun `mean of list is correct`() {
        val list = listOf(1f, 2f, 3f, 4f, 5f)
        assertEquals(3f, list.mean())
    }

    @Test
    fun `median of odd list is middle element`() {
        val list = listOf(1f, 3f, 2f)
        assertEquals(2f, list.median())
    }

    @Test
    fun `median of even list is average of middle elements`() {
        val list = listOf(1f, 2f, 3f, 4f)
        assertEquals(2.5f, list.median())
    }

    @Test
    fun `stdev of single element is 0`() {
        assertEquals(0f, listOf(1f).stdev())
    }

    @Test
    fun `stdev uses Bessel correction (n-1)`() {
        val list = listOf(10f, 12f, 23f, 23f, 16f, 23f, 21f, 16f)
        // Mean = 18.625
        // Variance (biased, n) = 23.98
        // Variance (unbiased, n-1) = 27.4107
        // Stdev (unbiased) = sqrt(27.4107) = 5.2355
        assertEquals(5.237f, list.stdev(), 0.001f)
    }

    @Test
    fun `meanOrNull returns null for empty list and correct value otherwise`() {
        assertEquals(null, emptyList<Float>().meanOrNull())
        assertEquals(3f, listOf(1f, 2f, 3f, 4f, 5f).meanOrNull())
    }

    @Test
    fun `medianOrNull for Float returns null on empty and correct value for odd and even`() {
        assertEquals(null, emptyList<Float>().medianOrNull())
        assertEquals(2f, listOf(1f, 3f, 2f).medianOrNull())
        assertEquals(2.5f, listOf(1f, 2f, 3f, 4f).medianOrNull())
    }

    @Test
    fun `medianOrNull for Int returns null on empty and correct value for odd and even`() {
        assertEquals(null, emptyList<Int>().medianOrNull())
        assertEquals(2f, listOf(1, 3, 2).medianOrNull())
        assertEquals(2.5f, listOf(1, 2, 3, 4).medianOrNull())
    }

    @Test
    fun `stdevOrNull for Float returns null on fewer than 2 elements and sample stdev otherwise`() {
        assertEquals(null, emptyList<Float>().stdevOrNull())
        assertEquals(null, listOf(1f).stdevOrNull())
        val list = listOf(10f, 12f, 23f, 23f, 16f, 23f, 21f, 16f)
        assertEquals(5.237f, list.stdevOrNull()!!, 0.001f)
    }

    @Test
    fun `stdevOrNull for Int returns null on fewer than 2 elements and sample stdev otherwise`() {
        assertEquals(null, emptyList<Int>().stdevOrNull())
        assertEquals(null, listOf(1).stdevOrNull())
        val list = listOf(10, 12, 23, 23, 16, 23, 21, 16)
        assertEquals(5.237f, list.stdevOrNull()!!, 0.001f)
    }

    @Test
    fun `percentile returns correct interpolated values`() {
        val list = listOf(10, 20, 30, 40, 50)
        assertEquals(10, list.percentile(0.0))
        assertEquals(20, list.percentile(0.25))
        assertEquals(30, list.percentile(0.5))
        assertEquals(40, list.percentile(0.75))
        assertEquals(50, list.percentile(1.0))
    }

    @Test
    fun `percentile throws on empty list`() {
        assertFailsWith<IllegalArgumentException> {
            emptyList<Int>().percentile(0.5)
        }
    }

    @Test
    fun `percentile throws on out of bounds p`() {
        val list = listOf(10, 20, 30)
        assertFailsWith<IllegalArgumentException> {
            list.percentile(-0.01)
        }
        assertFailsWith<IllegalArgumentException> {
            list.percentile(1.01)
        }
    }
}

