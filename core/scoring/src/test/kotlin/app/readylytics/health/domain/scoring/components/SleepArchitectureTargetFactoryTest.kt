package app.readylytics.health.domain.scoring.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SleepArchitectureTargetFactoryTest {
    @Test
    fun `targets stay close to the retired Ohayon bands`() {
        assertEquals(0.20f, SleepArchitectureTargetFactory.create(25).deepPercentage, 0.01f)
        assertEquals(0.18f, SleepArchitectureTargetFactory.create(40).deepPercentage, 0.01f)
        assertEquals(0.15f, SleepArchitectureTargetFactory.create(55).deepPercentage, 0.01f)
        assertEquals(0.12f, SleepArchitectureTargetFactory.create(70).deepPercentage, 0.011f)

        assertEquals(0.22f, SleepArchitectureTargetFactory.create(25).remPercentage, 0.01f)
        assertEquals(0.21f, SleepArchitectureTargetFactory.create(40).remPercentage, 0.01f)
        assertEquals(0.20f, SleepArchitectureTargetFactory.create(55).remPercentage, 0.01f)
        assertEquals(0.19f, SleepArchitectureTargetFactory.create(70).remPercentage, 0.01f)
    }

    @Test
    fun `no discontinuity at former band edges`() {
        listOf(29, 49, 59).forEach { age ->
            val before = SleepArchitectureTargetFactory.create(age)
            val after = SleepArchitectureTargetFactory.create(age + 1)
            assertTrue(
                "deep jumped at $age",
                abs(after.deepPercentage - before.deepPercentage) < 0.005f,
            )
            assertTrue(
                "rem jumped at $age",
                abs(after.remPercentage - before.remPercentage) < 0.005f,
            )
        }
    }

    @Test
    fun `targets are clamped at both extremes`() {
        val young = SleepArchitectureTargetFactory.create(10)
        assertTrue(young.deepPercentage <= 0.22f)
        assertTrue(young.remPercentage <= 0.23f)

        val old = SleepArchitectureTargetFactory.create(120)
        assertEquals(0.12f, old.deepPercentage, 0.0001f)
        assertEquals(0.18f, old.remPercentage, 0.0001f)
    }
}
