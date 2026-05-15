package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessPAIModulationTest {
    private val modulation = ReadinessPAIModulation()

    @Test
    fun `cap unchanged when readiness null`() {
        val r = modulation.computeDailyCap(readinessScore = null)
        assertEquals(ScoringConstants.Pai.DAILY_CAP, r.dailyCap, 0.001f)
        assertEquals(1f, r.adjustment, 0.001f)
        assertTrue(r.message.contains("not yet available"))
    }

    @Test
    fun `cap unchanged when user override active`() {
        val r = modulation.computeDailyCap(readinessScore = 30f, userOverride = true)
        assertEquals(ScoringConstants.Pai.DAILY_CAP, r.dailyCap, 0.001f)
        assertTrue(r.message.contains("override"))
    }

    @Test
    fun `readiness below 40 yields lowest cap`() {
        val r = modulation.computeDailyCap(readinessScore = 30f)
        assertEquals(ReadinessPAIModulation.CAP_LOW, r.dailyCap, 0.001f)
        assertTrue(r.message.contains("Focus on recovery"))
    }

    @Test
    fun `readiness 40-59 yields mid cap`() {
        val r = modulation.computeDailyCap(readinessScore = 50f)
        assertEquals(ReadinessPAIModulation.CAP_MID, r.dailyCap, 0.001f)
    }

    @Test
    fun `readiness 60-69 yields near-baseline cap`() {
        val r = modulation.computeDailyCap(readinessScore = 65f)
        assertEquals(ReadinessPAIModulation.CAP_NEAR_BASELINE, r.dailyCap, 0.001f)
    }

    @Test
    fun `readiness 70 plus yields normal cap`() {
        val r = modulation.computeDailyCap(readinessScore = 80f)
        assertEquals(ScoringConstants.Pai.DAILY_CAP, r.dailyCap, 0.001f)
    }

    @Test
    fun `applyCap caps daily PAI when readiness low`() {
        val capped = modulation.applyCap(dailyPai = 75f, readinessScore = 30f)
        assertEquals(ReadinessPAIModulation.CAP_LOW, capped, 0.001f)
    }

    @Test
    fun `applyCap leaves daily PAI unchanged below cap`() {
        val capped = modulation.applyCap(dailyPai = 30f, readinessScore = 30f)
        assertEquals(30f, capped, 0.001f) // already under the low cap
    }

    @Test
    fun `applyCap uses base cap when override active`() {
        val capped = modulation.applyCap(dailyPai = 60f, readinessScore = 25f, userOverride = true)
        // base cap 75; PAI 60 < cap so unchanged
        assertEquals(60f, capped, 0.001f)
    }

    @Test
    fun `cap adjustment ratio reflects bin`() {
        val low = modulation.computeDailyCap(readinessScore = 30f)
        val full = modulation.computeDailyCap(readinessScore = 80f)
        assertTrue(low.adjustment < full.adjustment)
        assertEquals(1f, full.adjustment, 0.001f)
    }

    @Test
    fun `feedback loop stability - PAI cap stable across days when readiness stable`() {
        // Two consecutive days at the same readiness should produce the same cap (no oscillation).
        val day1 = modulation.computeDailyCap(readinessScore = 55f)
        val day2 = modulation.computeDailyCap(readinessScore = 55f)
        assertEquals(day1.dailyCap, day2.dailyCap, 0.001f)
    }

    @Test
    fun `monotonic cap progression - higher readiness yields equal or higher cap`() {
        val readinessValues = listOf(20f, 40f, 50f, 60f, 70f, 90f)
        val caps = readinessValues.map { modulation.computeDailyCap(readinessScore = it).dailyCap }
        for (i in 1 until caps.size) {
            assertTrue("Cap should be monotonically non-decreasing: ${caps[i - 1]} -> ${caps[i]}", caps[i] >= caps[i - 1])
        }
    }
}
