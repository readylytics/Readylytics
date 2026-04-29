package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.5f

class SleepScoreTest {

    // -- computeDurationSubScore --

    @Test
    fun `duration sub-score is 100 when goal exactly met at 100 efficiency`() {
        val score = ScoringCalculator.computeDurationSubScore(
            durationMinutes = 480,
            efficiency = 100f,
            goalSleepHours = 8f,
        )
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `duration sub-score is 0 when duration is 0`() {
        val score = ScoringCalculator.computeDurationSubScore(0, 85f, 8f)
        assertEquals(0f, score, DELTA)
    }

    @Test
    fun `duration sub-score is capped at 100 even if duration exceeds goal`() {
        val score = ScoringCalculator.computeDurationSubScore(600, 100f, 8f)
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `duration sub-score is scaled by efficiency`() {
        val score = ScoringCalculator.computeDurationSubScore(480, 80f, 8f)
        assertEquals(80f, score, DELTA)
    }

    // -- computeArchSubScore --

    @Test
    fun `arch sub-score is 100 when both deep and REM are at optimal percent`() {
        val score = ScoringCalculator.computeArchSubScore(
            deepSleepMinutes = 96,   // 20% of 480
            remSleepMinutes = 96,
            durationMinutes = 480,
        )
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `arch sub-score is 0 when duration is 0`() {
        assertEquals(0f, ScoringCalculator.computeArchSubScore(0, 0, 0), DELTA)
    }

    @Test
    fun `arch sub-score is partial when only deep sleep is present`() {
        val score = ScoringCalculator.computeArchSubScore(96, 0, 480)
        assertEquals(50f, score, DELTA)
    }

    // -- computeSleepScore (integration of sub-scores) --

    @Test
    fun `sleep score is 100 for perfect night`() {
        val sRest = 100f
        val score = ScoringCalculator.computeSleepScore(
            durationMinutes = 480,
            efficiency = 100f,
            deepSleepMinutes = 96,
            remSleepMinutes = 96,
            goalSleepHours = 8f,
            sRest = sRest,
        )
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `sleep score is 0 for empty session`() {
        val score = ScoringCalculator.computeSleepScore(0, 0f, 0, 0, 8f, 0f)
        assertEquals(0f, score, DELTA)
    }

    @Test
    fun `sleep score respects 50-25-25 weighting`() {
        // duration 100, arch 0, rest 0  → expected = 50
        val score = ScoringCalculator.computeSleepScore(
            durationMinutes = 480,
            efficiency = 100f,
            deepSleepMinutes = 0,
            remSleepMinutes = 0,
            goalSleepHours = 8f,
            sRest = 0f,
        )
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `sleep score is within 0-100 range for arbitrary inputs`() {
        val score = ScoringCalculator.computeSleepScore(320, 75f, 50, 40, 8f, 60f)
        assertTrue(score in 0f..100f)
    }
}
