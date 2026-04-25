package com.gregor.lauritz.healthdashboard.domain.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class MovingAverageCalculatorTest {
    @Test
    fun `median of odd-length list`() {
        val values = listOf(1f, 2f, 3f, 4f, 5f)
        assertEquals(3f, MovingAverageCalculator.median(values))
    }

    @Test
    fun `median of even-length list`() {
        val values = listOf(1f, 2f, 3f, 4f)
        assertEquals(2.5f, MovingAverageCalculator.median(values))
    }

    @Test
    fun `median of empty list returns 0`() {
        assertEquals(0f, MovingAverageCalculator.median(emptyList()))
    }

    @Test
    fun `median with unsorted values`() {
        val values = listOf(5f, 1f, 3f, 2f, 4f)
        assertEquals(3f, MovingAverageCalculator.median(values))
    }

    @Test
    fun `calculateMovingAverages filters by date range`() {
        val now = 30 * 24 * 60 * 60 * 1000L
        val values = mapOf(
            (now - 35 * 24 * 60 * 60 * 1000L) to 10f, // 35 days ago, outside 30d
            (now - 25 * 24 * 60 * 60 * 1000L) to 20f, // 25 days ago, in 30d only
            (now - 5 * 24 * 60 * 60 * 1000L) to 30f,  // 5 days ago, in both
        )

        val (avg7d, avg30d) = MovingAverageCalculator.calculateMovingAverages(values, now)

        assertEquals(30f, avg7d)
        assertEquals(25f, avg30d)
    }
}
