package app.readylytics.health.domain.scoring.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.05f

class SleepContinuityCurvesTest {
    @Test
    fun `efficiency curve has no step at the old 90 percent boundary`() {
        val at89 = SleepContinuityCurves.efficiencyTerm(89f)
        val at90 = SleepContinuityCurves.efficiencyTerm(90f)

        assertTrue("delta was ${at90 - at89}", at90 - at89 in 0f..2f)
    }

    @Test
    fun `perfect efficiency scores exactly 100`() {
        assertEquals(100f, SleepContinuityCurves.efficiencyTerm(100f), 0.05f)
    }

    @Test
    fun `efficiency curve matches reference points`() {
        assertEquals(92.1f, SleepContinuityCurves.efficiencyTerm(90f), 0.2f)
        assertEquals(39.6f, SleepContinuityCurves.efficiencyTerm(75f), 0.2f)
    }

    @Test
    fun `hitting the sleep goal scores exactly 100`() {
        assertEquals(100f, SleepContinuityCurves.durationTerm(1f, 1.25f), DELTA)
    }

    @Test
    fun `duration curve matches reference points below goal`() {
        assertEquals(93.2f, SleepContinuityCurves.durationTerm(0.875f, 1.25f), 0.2f)
        assertEquals(75.3f, SleepContinuityCurves.durationTerm(0.75f, 1.25f), 0.2f)
        assertEquals(18.8f, SleepContinuityCurves.durationTerm(0.5f, 1.25f), 0.2f)
    }

    @Test
    fun `dead zone is flat then decays`() {
        assertEquals(100f, SleepContinuityCurves.durationTerm(1.24f, 1.25f), DELTA)
        assertEquals(100f, SleepContinuityCurves.durationTerm(1.25f, 1.25f), DELTA)
        assertEquals(82.3f, SleepContinuityCurves.durationTerm(1.5f, 1.25f), 0.5f)
        assertTrue(SleepContinuityCurves.durationTerm(1.75f, 1.25f) < SleepContinuityCurves.durationTerm(1.5f, 1.25f))
    }

    @Test
    fun `configurable onset makes the penalty start earlier`() {
        assertTrue(SleepContinuityCurves.durationTerm(1.2f, 1.0f) < 100f)
        assertEquals(100f, SleepContinuityCurves.durationTerm(1.2f, 1.25f), DELTA)
    }

    @Test
    fun `normal night is not penalized for fragmentation`() {
        assertEquals(100f, SleepContinuityCurves.fragmentationTerm(20f, 2), DELTA)
        assertEquals(100f, SleepContinuityCurves.fragmentationTerm(0f, 0), DELTA)
    }

    @Test
    fun `same WASO split across more awakenings scores lower`() {
        val oneBlock = SleepContinuityCurves.fragmentationTerm(40f, 1)
        val eightWakes = SleepContinuityCurves.fragmentationTerm(40f, 8)

        assertTrue("$eightWakes should be below $oneBlock", eightWakes < oneBlock)
        assertEquals(81.9f, oneBlock, 0.5f)
        assertEquals(50.7f, eightWakes, 0.5f)
    }

    @Test
    fun `regularity multiplier is penalty only`() {
        assertEquals(1f, SleepContinuityCurves.regularityMultiplier(null), DELTA)
        assertEquals(1f, SleepContinuityCurves.regularityMultiplier(100f), DELTA)
        assertEquals(0.92f, SleepContinuityCurves.regularityMultiplier(0f), DELTA)
        assertEquals(0.96f, SleepContinuityCurves.regularityMultiplier(50f), DELTA)
        assertEquals(1f, SleepContinuityCurves.regularityMultiplier(140f), DELTA)
    }
}
