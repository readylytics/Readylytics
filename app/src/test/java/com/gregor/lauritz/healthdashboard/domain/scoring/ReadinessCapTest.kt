package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.01f

// computeReadinessScore: recovery flags can cap the final readiness score.
// OVERREACHING → cap 70; ILLNESS_ONSET → cap 50; both → min cap (50); empty → no cap.
// REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng
class ReadinessCapTest {
    private val calculator = ScoringCalculatorImpl()

    private fun readiness(
        sRest: Float = 80f,
        sleepScore: Float = 80f,
        loadScore: Float = 80f,
        flags: Set<RecoveryFlag> = emptySet(),
    ) = calculator.computeReadinessScore(sRest, sleepScore, loadScore, flags)

    // Baseline uncapped score: 0.4*80 + 0.3*80 + 0.3*80 = 80
    private val baseUncapped = 80f

    @Test
    fun `no flags — score is uncapped`() {
        assertEquals(baseUncapped, readiness(), DELTA)
    }

    @Test
    fun `overreaching flag caps score at 70`() {
        val score = readiness(flags = setOf(RecoveryFlag.OVERREACHING))
        assertEquals(70f, score, DELTA)
    }

    @Test
    fun `illness onset flag caps score at 50`() {
        val score = readiness(flags = setOf(RecoveryFlag.ILLNESS_ONSET))
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `both flags apply most restrictive cap (50)`() {
        val score = readiness(flags = setOf(RecoveryFlag.OVERREACHING, RecoveryFlag.ILLNESS_ONSET))
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `score already below cap is not lifted`() {
        // If the raw score is 30, overreaching cap of 70 must not raise it
        val score = readiness(sRest = 20f, sleepScore = 20f, loadScore = 20f,
                              flags = setOf(RecoveryFlag.OVERREACHING))
        assertTrue("Cap must not lift a low score, was $score", score <= 70f)
        // 0.4*20+0.3*20+0.3*20 = 20 → still 20 after cap
        assertEquals(20f, score, DELTA)
    }

    @Test
    fun `non-capping flags do not affect readiness score`() {
        val flagsOnly = setOf(
            RecoveryFlag.CALIBRATING,
            RecoveryFlag.HRV_MISSING,
            RecoveryFlag.STAGES_MISSING,
            RecoveryFlag.NADIR_DELAYED,
        )
        assertEquals(baseUncapped, readiness(flags = flagsOnly), DELTA)
    }

    @Test
    fun `readiness is clamped to 0 at minimum`() {
        val score = readiness(sRest = 0f, sleepScore = 0f, loadScore = 0f)
        assertEquals(0f, score, DELTA)
    }

    @Test
    fun `readiness is clamped to 100 at maximum`() {
        val score = readiness(sRest = 100f, sleepScore = 100f, loadScore = 100f)
        assertEquals(100f, score, DELTA)
    }
}
