package app.readylytics.health.core.model.domain.scoring

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrainingReadinessConfigTest {
    @Test
    fun `fromStored preserves finite values within the persistence ranges`() {
        val config = TrainingReadinessConfig.fromStored(scale = 77.3f, weight = 0.83f)

        assertEquals(77.3f, config.residualFatigueScale, 0f)
        assertEquals(0.83f, config.loadBalanceWeight, 0f)
    }

    @Test
    fun `fromStored clamps finite values outside the persistence ranges`() {
        val tooLow = TrainingReadinessConfig.fromStored(scale = 10f, weight = 0.1f)
        val tooHigh = TrainingReadinessConfig.fromStored(scale = 500f, weight = 2f)

        assertEquals(SettingsDefaults.MIN_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE, tooLow.residualFatigueScale, 0f)
        assertEquals(SettingsDefaults.MIN_TRAINING_READINESS_LOAD_BALANCE_WEIGHT, tooLow.loadBalanceWeight, 0f)
        assertEquals(SettingsDefaults.MAX_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE, tooHigh.residualFatigueScale, 0f)
        assertEquals(SettingsDefaults.MAX_TRAINING_READINESS_LOAD_BALANCE_WEIGHT, tooHigh.loadBalanceWeight, 0f)
    }

    @Test
    fun `fromStored falls back to defaults for non-finite values`() {
        val config =
            TrainingReadinessConfig.fromStored(
                scale = Float.NaN,
                weight = Float.POSITIVE_INFINITY,
            )

        assertEquals(SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE, config.residualFatigueScale, 0f)
        assertEquals(SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT, config.loadBalanceWeight, 0f)
    }

    @Test
    fun `direct construction rejects non-finite and out-of-range parameters`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrainingReadinessConfig(Float.NaN, 0.9f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainingReadinessConfig(100f, Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainingReadinessConfig(74f, 0.9f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainingReadinessConfig(100f, 0.79f)
        }
    }
}
