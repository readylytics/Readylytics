package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.01f

// computeHrvScore: linear below z=1.5; soft saturation above.
// REF: spec §4.2; Bellenger 2017 Front Physiol
class HrvScoreTest {

    @Test
    fun `z=-3 clamps to 0`() {
        // 50 + 25 * (-3) = -25 → clamped to 0
        assertEquals(0f, ScoringCalculator.computeHrvScore(-3f), DELTA)
    }

    @Test
    fun `z=-2 gives 0`() {
        // 50 + 25 * (-2) = 0
        assertEquals(0f, ScoringCalculator.computeHrvScore(-2f), DELTA)
    }

    @Test
    fun `z=-1 gives 25`() {
        assertEquals(25f, ScoringCalculator.computeHrvScore(-1f), DELTA)
    }

    @Test
    fun `z=0 gives 50`() {
        assertEquals(50f, ScoringCalculator.computeHrvScore(0f), DELTA)
    }

    @Test
    fun `z=1_5 gives 87_5`() {
        // 50 + 25 * 1.5 = 87.5
        assertEquals(87.5f, ScoringCalculator.computeHrvScore(1.5f), DELTA)
    }

    @Test
    fun `z=2_0 gives 90_625 (saturated)`() {
        // adjustedZ = 1.5 + 0.25*(2-1.5) = 1.5 + 0.125 = 1.625
        // 50 + 25 * 1.625 = 90.625
        assertEquals(90.625f, ScoringCalculator.computeHrvScore(2f), DELTA)
    }

    @Test
    fun `z=3_0 gives 96_875 (saturated)`() {
        // adjustedZ = 1.5 + 0.25*(3-1.5) = 1.5 + 0.375 = 1.875
        // 50 + 25 * 1.875 = 96.875
        assertEquals(96.875f, ScoringCalculator.computeHrvScore(3f), DELTA)
    }

    @Test
    fun `z=3 is strictly less than 100 (soft saturation)`() {
        assertTrue("Score at z=3 should be < 100", ScoringCalculator.computeHrvScore(3f) < 100f)
    }

    @Test
    fun `slope above z=1_5 is shallower than below`() {
        val slopeBelow = ScoringCalculator.computeHrvScore(1.5f) - ScoringCalculator.computeHrvScore(0.5f)
        val slopeAbove = ScoringCalculator.computeHrvScore(2.5f) - ScoringCalculator.computeHrvScore(1.5f)
        assertTrue("Slope above 1.5 ($slopeAbove) should be less than slope below ($slopeBelow)", slopeAbove < slopeBelow)
    }

    @Test
    fun `result never exceeds 100`() {
        for (z in listOf(1.5f, 2f, 3f, 5f, 10f)) {
            assertTrue("Score at z=$z should be <= 100", ScoringCalculator.computeHrvScore(z) <= 100f)
        }
    }

    @Test
    fun `result never goes below 0`() {
        for (z in listOf(-1.5f, -2f, -3f, -5f, -10f)) {
            assertTrue("Score at z=$z should be >= 0", ScoringCalculator.computeHrvScore(z) >= 0f)
        }
    }
}
