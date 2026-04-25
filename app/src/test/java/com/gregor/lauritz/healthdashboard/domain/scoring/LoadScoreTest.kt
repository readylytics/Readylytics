package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.01f

class LoadScoreTest {
    @Test
    fun `zero SR is optimal`() = assertEquals(85f, ScoringCalculator.computeLoadScore(0f), DELTA)

    @Test
    fun `below 0_8 is neutral`() = assertEquals(50f, ScoringCalculator.computeLoadScore(0.5f), DELTA)

    @Test
    fun `at 0_8 boundary is optimal`() = assertEquals(100f, ScoringCalculator.computeLoadScore(0.8f), DELTA)

    @Test
    fun `at 1_0 is optimal`() = assertEquals(100f, ScoringCalculator.computeLoadScore(1.0f), DELTA)

    @Test
    fun `at 1_2 boundary is optimal`() = assertEquals(100f, ScoringCalculator.computeLoadScore(1.2f), DELTA)

    @Test
    fun `at 1_35 is fatigued midpoint`() = assertEquals(85f, ScoringCalculator.computeLoadScore(1.35f), DELTA)

    @Test
    fun `at 1_5 is warning boundary`() = assertEquals(70f, ScoringCalculator.computeLoadScore(1.5f), DELTA)

    @Test
    fun `above 1_5 is poor`() = assertEquals(30f, ScoringCalculator.computeLoadScore(2.0f), DELTA)
}
