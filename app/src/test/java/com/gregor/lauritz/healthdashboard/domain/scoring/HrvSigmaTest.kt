package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.001f

class HrvSigmaTest {

    @Test
    fun `empty list returns MIN_SIGMA`() {
        val sigma = ScoringCalculator.hrvSigma(emptyList())
        assertEquals(ScoringConstants.Restoration.MIN_SIGMA, sigma, DELTA)
    }

    @Test
    fun `single value uses provisional CV rule`() {
        val hrv = 50f
        val sigma = ScoringCalculator.hrvSigma(listOf(hrv))
        val expected = hrv * ScoringConstants.Restoration.PROVISIONAL_CV_RULE
        assertEquals(expected, sigma, DELTA)
    }

    @Test
    fun `fewer than 21 values uses provisional CV rule`() {
        val values = List(10) { 40f }
        val sigma = ScoringCalculator.hrvSigma(values)
        val expected = 40f * ScoringConstants.Restoration.PROVISIONAL_CV_RULE
        assertEquals(expected, sigma, DELTA)
    }

    @Test
    fun `21 or more values uses standard deviation`() {
        // 21 values with known stdev
        val values = List(21) { it.toFloat() }  // 0..20, stdev ≈ 6.2
        val sigma = ScoringCalculator.hrvSigma(values)
        assertTrue(sigma > 5f)  // stdev of 0..20 is ~6.2, well above provisional
    }

    @Test
    fun `constant values with 21+ entries fall back to provisional rule`() {
        val values = List(21) { 50f }  // stdev = 0 → provisional rule applies
        val sigma = ScoringCalculator.hrvSigma(values)
        val expected = 50f * ScoringConstants.Restoration.PROVISIONAL_CV_RULE
        assertEquals(expected, sigma, DELTA)
    }

    @Test
    fun `sigma is always positive`() {
        val cases = listOf(
            emptyList(),
            listOf(30f),
            List(5) { 50f + it },
            List(25) { 60f + it * 0.5f },
        )
        for (values in cases) {
            assertTrue("sigma must be > 0 for $values", ScoringCalculator.hrvSigma(values) > 0f)
        }
    }
}
