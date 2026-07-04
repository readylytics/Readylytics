package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.01f

// computeReadinessScore: only illness onset caps the final readiness score.
// ILLNESS_ONSET → cap 50; empty/legacy strong recovery flags → no cap.
// REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng
class ReadinessCapTest {
    private val calculator = LoadScoringStrategy()

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
    fun `strong recovery signal does not cap readiness`() {
        val score = readiness(flags = setOf(RecoveryFlag.STRONG_RECOVERY_SIGNAL))
        assertEquals(baseUncapped, score, DELTA)
    }

    @Test
    fun `illness onset flag caps score at 50`() {
        val score = readiness(flags = setOf(RecoveryFlag.ILLNESS_ONSET))
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `legacy overreaching flag does not cap readiness`() {
        val score = readiness(flags = setOf(RecoveryFlag.OVERREACHING))
        assertEquals(baseUncapped, score, DELTA)
    }

    @Test
    fun `illness onset caps score even with legacy overreaching flag`() {
        val score = readiness(flags = setOf(RecoveryFlag.OVERREACHING, RecoveryFlag.ILLNESS_ONSET))
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `score already below cap is not lifted`() {
        // If the raw score is 20, illness cap of 50 must not raise it
        val score =
            readiness(
                sRest = 20f,
                sleepScore = 20f,
                loadScore = 20f,
                flags = setOf(RecoveryFlag.ILLNESS_ONSET),
            )
        assertTrue("Cap must not lift a low score, was $score", score <= 50f)
        // 0.4*20+0.3*20+0.3*20 = 20 → still 20 after cap
        assertEquals(20f, score, DELTA)
    }

    @Test
    fun `non-capping flags do not affect readiness score`() {
        val flagsOnly =
            setOf(
                RecoveryFlag.CALIBRATING,
                RecoveryFlag.HRV_MISSING,
                RecoveryFlag.STAGES_MISSING,
                RecoveryFlag.NADIR_DELAYED,
                RecoveryFlag.STRONG_RECOVERY_SIGNAL,
                RecoveryFlag.OVERREACHING,
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
