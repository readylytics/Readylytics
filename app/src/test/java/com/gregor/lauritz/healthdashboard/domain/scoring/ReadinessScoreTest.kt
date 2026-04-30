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
    fun `paradoxical response cap is applied when HRV z-score and RHR ratio are both high`() {
        // zHrv > 2.0 and rhrRatio > 1.05 → score capped at 60
        val score = ScoringCalculator.computeReadinessScore(
            sRest = 100f,
            sleepScore = 100f,
            loadScore = 100f,
            zHrv = 3.0f,
            rhrRatio = 1.1f,
        )
        assertEquals(60f, score, DELTA)
    }

    @Test
    fun `paradoxical cap is NOT applied when only HRV z-score is high`() {
        val score = ScoringCalculator.computeReadinessScore(
            sRest = 100f,
            sleepScore = 100f,
            loadScore = 100f,
            zHrv = 3.0f,
            rhrRatio = 1.0f,  // below threshold
        )
        assertTrue(score > 60f)
    }

    @Test
    fun `paradoxical cap is NOT applied when only RHR ratio is high`() {
        val score = ScoringCalculator.computeReadinessScore(
            sRest = 100f,
            sleepScore = 100f,
            loadScore = 100f,
            zHrv = 1.5f,  // below threshold
            rhrRatio = 1.1f,
        )
        assertTrue(score > 60f)
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
