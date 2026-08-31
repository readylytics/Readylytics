package app.readylytics.health.core.model.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.0001f

class SleepScoreWeightProfileTest {
    @Test
    fun `every profile sums to one`() {
        SleepScoreWeightProfile.entries.forEach { profile ->
            val sum =
                profile.durationWeight + profile.architectureWeight +
                    profile.restorationWeight + profile.fragmentationWeight
            assertEquals("$profile", 1f, sum, DELTA)
        }
    }

    @Test
    fun `degraded weights renormalize the surviving terms`() {
        SleepScoreWeightProfile.entries.forEach { profile ->
            assertEquals(
                "$profile",
                1f,
                profile.degradedDurationWeight + profile.degradedRestorationWeight,
                DELTA,
            )
        }
        assertEquals(0.6154f, SleepScoreWeightProfile.BALANCED.degradedDurationWeight, 0.001f)
        assertEquals(0.3846f, SleepScoreWeightProfile.BALANCED.degradedRestorationWeight, 0.001f)
    }

    @Test
    fun `default profile is balanced`() {
        assertEquals(SleepScoreWeightProfile.BALANCED, SleepScoreWeightProfile.DEFAULT)
        assertEquals(0.40f, SleepScoreWeightProfile.BALANCED.durationWeight, DELTA)
        assertEquals(0.20f, SleepScoreWeightProfile.BALANCED.architectureWeight, DELTA)
        assertEquals(0.25f, SleepScoreWeightProfile.BALANCED.restorationWeight, DELTA)
        assertEquals(0.15f, SleepScoreWeightProfile.BALANCED.fragmentationWeight, DELTA)
    }
}
