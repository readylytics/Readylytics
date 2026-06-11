package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.001f

class ReadinessScoreTest {
    private val calculator = LoadScoringStrategy()

    @Test
    fun `readiness score is 100 for ideal inputs`() {
        val score =
            calculator.computeReadinessScore(
                sRest = 100f,
                sleepScore = 100f,
                loadScore = 100f,
                recoveryFlags = emptySet(),
            )
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `readiness score is 0 for all-zero inputs`() {
        assertEquals(0f, calculator.computeReadinessScore(0f, 0f, 0f, emptySet()), DELTA)
    }

    @Test
    fun `readiness score is clamped to 100 even when base exceeds it`() {
        val score = calculator.computeReadinessScore(120f, 120f, 120f, emptySet())
        assertEquals(100f, score, DELTA)
    }

    @Test
    fun `readiness score is clamped to 0 for negative inputs`() {
        val score = calculator.computeReadinessScore(-100f, -100f, -100f, emptySet())
        assertEquals(0f, score, DELTA)
    }

    @Test
    fun `overreaching flag caps readiness score`() {
        // Base score = 100
        val score =
            calculator.computeReadinessScore(
                100f,
                100f,
                100f,
                setOf(RecoveryFlag.OVERREACHING),
            )
        assertEquals(ScoringConstants.Readiness.OVERREACHING_MAX_SCORE, score, DELTA)
    }

    @Test
    fun `illness onset flag caps readiness score`() {
        // Base score = 100
        val score =
            calculator.computeReadinessScore(
                100f,
                100f,
                100f,
                setOf(RecoveryFlag.ILLNESS_ONSET),
            )
        assertEquals(ScoringConstants.Readiness.ILLNESS_MAX_SCORE, score, DELTA)
    }
}
