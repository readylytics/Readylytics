package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryFlagValidatorTest {
    private val validator = RecoveryFlagValidator()

    @Test
    fun `overreaching not flagged when only today triggered`() {
        val result =
            validator.assessOverreaching(
                zLnHrvToday = 2.0f,
                zRhrToday = -2.5f,
                zLnHrvYesterday = 0.5f,
                zRhrYesterday = 0.0f,
                consecutiveDays = 1,
            )
        assertNull(result)
    }

    @Test
    fun `overreaching not flagged when neither side meets thresholds`() {
        val result =
            validator.assessOverreaching(
                zLnHrvToday = 1.0f,
                zRhrToday = -1.0f,
                zLnHrvYesterday = 0.5f,
                zRhrYesterday = -1.5f,
                consecutiveDays = 0,
            )
        assertNull(result)
    }

    @Test
    fun `overreaching flagged when both nights triggered`() {
        val result =
            validator.assessOverreaching(
                zLnHrvToday = 1.8f,
                zRhrToday = -2.2f,
                zLnHrvYesterday = 1.6f,
                zRhrYesterday = -2.1f,
                consecutiveDays = 2,
            )
        assertNotNull(result)
        assertEquals(RecoveryFlag.OVERREACHING, result!!.flag)
        assertEquals(2, result.consecutiveDays)
        assertTrue("Confidence in (0,1]: ${result.confidence}", result.confidence > 0f)
        assertTrue("Confidence ≤ 1: ${result.confidence}", result.confidence <= 1f)
    }

    @Test
    fun `overreaching confidence grows with consecutive days`() {
        val day2 =
            validator.assessOverreaching(
                zLnHrvToday = 1.8f, zRhrToday = -2.2f,
                zLnHrvYesterday = 1.6f, zRhrYesterday = -2.1f,
                consecutiveDays = 2,
            )!!
        val day5 =
            validator.assessOverreaching(
                zLnHrvToday = 1.8f, zRhrToday = -2.2f,
                zLnHrvYesterday = 1.6f, zRhrYesterday = -2.1f,
                consecutiveDays = 5,
            )!!
        assertTrue("Day 5 > Day 2 confidence", day5.confidence >= day2.confidence)
    }

    @Test
    fun `overreaching confidence higher with deeper penetration`() {
        val shallow =
            validator.assessOverreaching(
                zLnHrvToday = 1.6f, zRhrToday = -2.1f,
                zLnHrvYesterday = 1.6f, zRhrYesterday = -2.1f,
                consecutiveDays = 2,
            )!!
        val deep =
            validator.assessOverreaching(
                zLnHrvToday = 2.8f, zRhrToday = -3.2f,
                zLnHrvYesterday = 2.7f, zRhrYesterday = -3.1f,
                consecutiveDays = 2,
            )!!
        assertTrue("Deep penetration → higher confidence", deep.confidence > shallow.confidence)
    }

    @Test
    fun `overreaching rationale contains threshold details`() {
        val result =
            validator.assessOverreaching(
                zLnHrvToday = 1.8f, zRhrToday = -2.2f,
                zLnHrvYesterday = 1.6f, zRhrYesterday = -2.1f,
                consecutiveDays = 2,
            )!!
        assertTrue(result.rationale.contains("zHRV="))
        assertTrue(result.rationale.contains("zRHR="))
        assertTrue(result.rationale.contains("consecutive nights"))
    }

    @Test
    fun `illness flagged when both nights triggered with high RHR delta`() {
        val result =
            validator.assessIllness(
                zLnHrvToday = -1.8f,
                zRhrToday = 2.2f,
                zLnHrvYesterday = -1.6f,
                zRhrYesterday = 2.1f,
                rhrDeltaBpm = 7f,
                consecutiveDays = 2,
            )
        assertNotNull(result)
        assertEquals(RecoveryFlag.ILLNESS_ONSET, result!!.flag)
    }

    @Test
    fun `illness flagged with high RHR delta even when zRHR borderline`() {
        // Today qualifies via delta route; yesterday via zRHR route
        val result =
            validator.assessIllness(
                zLnHrvToday = -1.8f,
                zRhrToday = 0f,
                zLnHrvYesterday = -1.6f,
                zRhrYesterday = 2.1f,
                rhrDeltaBpm = 8f, // ≥ 5 default threshold
                consecutiveDays = 2,
            )
        assertNotNull(result)
    }

    @Test
    fun `illness not flagged when only one night triggered`() {
        val result =
            validator.assessIllness(
                zLnHrvToday = -1.8f,
                zRhrToday = 2.2f,
                zLnHrvYesterday = 0.5f,
                zRhrYesterday = 0f,
                rhrDeltaBpm = 7f,
                consecutiveDays = 1,
            )
        assertNull(result)
    }

    @Test
    fun `illness returns null when inputs missing`() {
        val result =
            validator.assessIllness(
                zLnHrvToday = null,
                zRhrToday = 2.2f,
                zLnHrvYesterday = -1.6f,
                zRhrYesterday = 2.1f,
                rhrDeltaBpm = 7f,
                consecutiveDays = 2,
            )
        assertNull(result)
    }

    @Test
    fun `performance targets for OVERREACHING`() {
        val targets = validator.targetsFor(RecoveryFlag.OVERREACHING)!!
        assertEquals(0.70f, targets.sensitivity, 0.001f)
        assertEquals(0.80f, targets.specificity, 0.001f)
    }

    @Test
    fun `performance targets for ILLNESS_ONSET are more conservative`() {
        val targets = validator.targetsFor(RecoveryFlag.ILLNESS_ONSET)!!
        assertTrue(
            "ILLNESS specificity ≥ OVERREACHING specificity",
            targets.specificity >= validator.targetsFor(RecoveryFlag.OVERREACHING)!!.specificity,
        )
    }

    @Test
    fun `performance targets not defined for non-clinical flags`() {
        assertNull(validator.targetsFor(RecoveryFlag.HRV_MISSING))
        assertNull(validator.targetsFor(RecoveryFlag.CALIBRATING))
    }
}
