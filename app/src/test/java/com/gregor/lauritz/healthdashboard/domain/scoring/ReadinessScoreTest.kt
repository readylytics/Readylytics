package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.5f

class ReadinessScoreTest {

    @Test
    fun `readiness score is 100 for ideal inputs`() {
        val score = ScoringCalculator.computeReadinessScore(
            sRest = 100f,
            sleepScore = 100f,
            loadScore = 100f,
        )
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `readiness score is 0 for all-zero inputs`() {
        assertEquals(0f, ScoringCalculator.computeReadinessScore(0f, 0f, 0f), DELTA)
    }

    @Test
    fun `readiness score is clamped to 100 even when base exceeds it`() {
        val score = ScoringCalculator.computeReadinessScore(120f, 120f, 120f)
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `readiness score is clamped to 0 for negative inputs`() {
        val score = ScoringCalculator.computeReadinessScore(-100f, -100f, -100f)
        assertEquals(0f, score, DELTA)
    }

    @Test
    fun `overreaching cap is applied when flag is present`() {
        // OVERREACHING flag → score capped at 70
        val score = ScoringCalculator.computeReadinessScore(
            sRest = 100f,
            sleepScore = 100f,
            loadScore = 100f,
            recoveryFlags = setOf(RecoveryFlag.OVERREACHING),
        )
        assertEquals(70f, score, DELTA)
    }

    @Test
    fun `illness cap is applied when flag is present`() {
        // ILLNESS_ONSET flag → score capped at 50
        val score = ScoringCalculator.computeReadinessScore(
            sRest = 100f,
            sleepScore = 100f,
            loadScore = 100f,
            recoveryFlags = setOf(RecoveryFlag.ILLNESS_ONSET),
        )
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `readiness score respects 40-30-30 weighting`() {
        // rest=100, sleep=0, load=0  → expected 40
        val score = ScoringCalculator.computeReadinessScore(100f, 0f, 0f)
        assertEquals(40f, score, DELTA)
    }

    @Test
    fun `readiness score is within 0-100 for typical inputs`() {
        val score = ScoringCalculator.computeReadinessScore(75f, 80f, 65f)
        assertTrue(score in 0f..100f)
    }
}
