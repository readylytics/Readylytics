package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.5f   // acceptable rounding tolerance for quadratic outputs

// score = 100 * exp(-2.5 * max(0, SR - 1.3)^2), clamped [0, 100]
// All SR ≤ 1.3 → 100 (no penalty for rest or moderate load).
// REF: Gabbett 2016 BJSM; Windt & Gabbett 2018 BJSM; A.4 review
class LoadScoreTest {
    @Test
    fun `zero SR scores 100 — no penalty for rest days`() =
        assertEquals(100f, ScoringCalculator.computeLoadScore(0f), DELTA)

    @Test
    fun `under-training SR 0_5 scores 100`() =
        assertEquals(100f, ScoringCalculator.computeLoadScore(0.5f), DELTA)

    @Test
    fun `lower sweet-spot boundary 0_8 scores 100`() =
        assertEquals(100f, ScoringCalculator.computeLoadScore(0.8f), DELTA)

    @Test
    fun `ideal SR 1_0 scores 100`() =
        assertEquals(100f, ScoringCalculator.computeLoadScore(1.0f), DELTA)

    @Test
    fun `upper sweet-spot boundary 1_3 scores 100`() =
        assertEquals(100f, ScoringCalculator.computeLoadScore(1.3f), DELTA)

    @Test
    fun `SR 1_35 begins quadratic decay`() {
        // 100 * exp(-2.5 * 0.05^2) = 100 * exp(-0.00625) ≈ 99.4
        assertEquals(99.4f, ScoringCalculator.computeLoadScore(1.35f), DELTA)
    }

    @Test
    fun `SR 1_5 moderate overload`() {
        // 100 * exp(-2.5 * 0.2^2) = 100 * exp(-0.1) ≈ 90.5
        assertEquals(90.5f, ScoringCalculator.computeLoadScore(1.5f), DELTA)
    }

    @Test
    fun `SR 2_0 heavy overload`() {
        // 100 * exp(-2.5 * 0.7^2) = 100 * exp(-1.225) ≈ 29.4
        assertEquals(29.4f, ScoringCalculator.computeLoadScore(2.0f), DELTA)
    }

    @Test
    fun `SR 3_0 extreme overload approaches zero`() {
        // 100 * exp(-2.5 * 1.7^2) = 100 * exp(-7.225) < 1
        assert(ScoringCalculator.computeLoadScore(3.0f) < 1f)
    }

    @Test
    fun `score is monotonically non-increasing above sweet spot`() {
        val scores = listOf(1.3f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f)
            .map { ScoringCalculator.computeLoadScore(it) }
        for (i in 0 until scores.size - 1) {
            assert(scores[i] >= scores[i + 1]) {
                "Score should not increase: ${scores[i]} at index $i < ${scores[i + 1]}"
            }
        }
    }
}
