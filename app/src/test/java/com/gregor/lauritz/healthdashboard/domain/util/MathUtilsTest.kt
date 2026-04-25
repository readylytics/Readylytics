package com.gregor.lauritz.healthdashboard.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
