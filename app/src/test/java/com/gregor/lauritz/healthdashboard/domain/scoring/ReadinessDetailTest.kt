package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.model.ReadinessCappingReason
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.model.TrainingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.01f

class ReadinessDetailTest {
    private val calculator = ScoringCalculatorImpl()

    private fun detail(
        sRest: Float = 80f,
        sleep: Float = 80f,
        load: Float = 80f,
        flags: Set<RecoveryFlag> = emptySet(),
        strainRatio: Float? = null,
        consecutiveOverreachingDays: Int = 1,
        consecutiveIllnessDays: Int = 1,
    ) = calculator.computeReadinessScoreDetail(
        sRest = sRest,
        sleepScore = sleep,
        loadScore = load,
        recoveryFlags = flags,
        strainRatio = strainRatio,
        consecutiveOverreachingDays = consecutiveOverreachingDays,
        consecutiveIllnessDays = consecutiveIllnessDays,
    )

    // ─── Capping reason identification ────────────────────────────────────

    @Test
    fun `no flags reports NONE cap reason and null explanation`() {
        val d = detail()
        assertEquals(ReadinessCappingReason.NONE, d.cappingReason)
        assertNull(d.capExplanation)
        assertEquals(80f, d.score, DELTA)
    }

    @Test
    fun `overreaching flag reports OVERREACHING_CONSECUTIVE cap`() {
        val d = detail(flags = setOf(RecoveryFlag.OVERREACHING))
        assertEquals(ReadinessCappingReason.OVERREACHING_CONSECUTIVE, d.cappingReason)
        assertEquals(70f, d.score, DELTA)
        assertNotNull(d.capExplanation)
        assertTrue(d.capExplanation!!.contains("overreaching"))
    }

    @Test
    fun `illness flag reports ILLNESS_ONSET_CONSECUTIVE cap and takes precedence`() {
        val d = detail(flags = setOf(RecoveryFlag.OVERREACHING, RecoveryFlag.ILLNESS_ONSET))
        assertEquals(ReadinessCappingReason.ILLNESS_ONSET_CONSECUTIVE, d.cappingReason)
        assertEquals(50f, d.score, DELTA)
    }

    @Test
    fun `extreme strain ratio reports EXTREME_LOAD cap when above 2_0`() {
        val d = detail(strainRatio = 2.5f)
        assertEquals(ReadinessCappingReason.EXTREME_LOAD, d.cappingReason)
        assertEquals(60f, d.score, DELTA)
    }

    @Test
    fun `strain ratio exactly 2_0 does not cap`() {
        val d = detail(strainRatio = 2.0f)
        assertEquals(ReadinessCappingReason.NONE, d.cappingReason)
    }

    // ─── Escalating cap (day 1 vs day 3+) ─────────────────────────────────

    @Test
    fun `overreaching day 1 caps at 70`() {
        val d = detail(flags = setOf(RecoveryFlag.OVERREACHING), consecutiveOverreachingDays = 1)
        assertEquals(70f, d.score, DELTA)
    }

    @Test
    fun `overreaching day 3 caps at 55 (escalated)`() {
        val d = detail(flags = setOf(RecoveryFlag.OVERREACHING), consecutiveOverreachingDays = 3)
        assertEquals(55f, d.score, DELTA)
    }

    @Test
    fun `overreaching day 5 still capped at 55`() {
        val d = detail(flags = setOf(RecoveryFlag.OVERREACHING), consecutiveOverreachingDays = 5)
        assertEquals(55f, d.score, DELTA)
    }

    // ─── Recommendation generation ───────────────────────────────────────

    @Test
    fun `healthy score above 80 recommends FULL_EFFORT`() {
        val d = detail(sRest = 90f, sleep = 90f, load = 90f)
        assertEquals(TrainingAction.FULL_EFFORT, d.recommendation.action)
    }

    @Test
    fun `score in 60-79 recommends NORMAL`() {
        val d = detail(sRest = 75f, sleep = 75f, load = 75f)
        assertEquals(TrainingAction.NORMAL, d.recommendation.action)
    }

    @Test
    fun `score in 40-59 recommends LIGHT_ACTIVITY`() {
        val d = detail(sRest = 50f, sleep = 50f, load = 50f)
        assertEquals(TrainingAction.LIGHT_ACTIVITY, d.recommendation.action)
    }

    @Test
    fun `score below 40 recommends REST`() {
        val d = detail(sRest = 30f, sleep = 30f, load = 30f)
        assertEquals(TrainingAction.REST, d.recommendation.action)
    }

    @Test
    fun `overreaching cap recommends LIGHT_ACTIVITY regardless of score`() {
        val d = detail(flags = setOf(RecoveryFlag.OVERREACHING))
        assertEquals(TrainingAction.LIGHT_ACTIVITY, d.recommendation.action)
        assertTrue(d.recommendation.message.contains("HRV elevated"))
        assertEquals(4, d.recommendation.durationDays)
    }

    @Test
    fun `illness cap recommends REST`() {
        val d = detail(flags = setOf(RecoveryFlag.ILLNESS_ONSET))
        assertEquals(TrainingAction.REST, d.recommendation.action)
        assertTrue(d.recommendation.message.contains("infection") || d.recommendation.message.contains("symptoms"))
    }

    @Test
    fun `extreme load cap recommends LIGHT_ACTIVITY for 3 days`() {
        val d = detail(strainRatio = 2.5f)
        assertEquals(TrainingAction.LIGHT_ACTIVITY, d.recommendation.action)
        assertEquals(3, d.recommendation.durationDays)
    }

    // ─── Raw score preservation ──────────────────────────────────────────

    @Test
    fun `rawScore reflects uncapped weighted sum`() {
        val d = detail(sRest = 90f, sleep = 80f, load = 70f, flags = setOf(RecoveryFlag.OVERREACHING))
        assertEquals(0.4f * 90f + 0.3f * 80f + 0.3f * 70f, d.rawScore, DELTA)
    }
}
