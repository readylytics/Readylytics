package app.readylytics.health.core.model.domain.validation

import org.junit.Test
import kotlin.test.assertTrue

class SettingsValidatorsTest {
    @Test
    fun `BODY_TEMP_ELEVATED_THRESHOLD_RULE accepts the full 0_25 to 1_5 range and rejects outside it`() {
        assertTrue(SettingsValidators.BODY_TEMP_ELEVATED_THRESHOLD_RULE.validate(0.25f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.BODY_TEMP_ELEVATED_THRESHOLD_RULE.validate(1.0f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.BODY_TEMP_ELEVATED_THRESHOLD_RULE.validate(1.5f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.BODY_TEMP_ELEVATED_THRESHOLD_RULE.validate(0.2f) is ValidationResult.Invalid)
        assertTrue(SettingsValidators.BODY_TEMP_ELEVATED_THRESHOLD_RULE.validate(1.6f) is ValidationResult.Invalid)
    }

    @Test
    fun `FATIGUE_HALF_LIFE_RULE accepts the full 6 to 96 hour range and rejects outside it`() {
        assertTrue(SettingsValidators.FATIGUE_HALF_LIFE_RULE.validate(6f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.FATIGUE_HALF_LIFE_RULE.validate(24f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.FATIGUE_HALF_LIFE_RULE.validate(96f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.FATIGUE_HALF_LIFE_RULE.validate(5.9f) is ValidationResult.Invalid)
        assertTrue(SettingsValidators.FATIGUE_HALF_LIFE_RULE.validate(96.1f) is ValidationResult.Invalid)
    }

    @Test
    fun `FATIGUE_GAIN_RULE accepts the full 0_1 to 5_0 range and rejects outside it`() {
        assertTrue(SettingsValidators.FATIGUE_GAIN_RULE.validate(0.1f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.FATIGUE_GAIN_RULE.validate(1.0f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.FATIGUE_GAIN_RULE.validate(5.0f) is ValidationResult.Valid)
        assertTrue(SettingsValidators.FATIGUE_GAIN_RULE.validate(0.09f) is ValidationResult.Invalid)
        assertTrue(SettingsValidators.FATIGUE_GAIN_RULE.validate(5.1f) is ValidationResult.Invalid)
    }

    @Test
    fun `TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE_RULE accepts finite persistence values in range`() {
        val rule = SettingsValidators.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE_RULE

        assertTrue(rule.validate(75f) is ValidationResult.Valid)
        assertTrue(rule.validate(77.3f) is ValidationResult.Valid)
        assertTrue(rule.validate(175f) is ValidationResult.Valid)
        assertTrue(rule.validate(74.9f) is ValidationResult.Invalid)
        assertTrue(rule.validate(175.1f) is ValidationResult.Invalid)
    }

    @Test
    fun `TRAINING_READINESS_LOAD_BALANCE_WEIGHT_RULE accepts the full 0_8 to 1_0 range`() {
        val rule = SettingsValidators.TRAINING_READINESS_LOAD_BALANCE_WEIGHT_RULE

        assertTrue(rule.validate(0.8f) is ValidationResult.Valid)
        assertTrue(rule.validate(0.9f) is ValidationResult.Valid)
        assertTrue(rule.validate(1f) is ValidationResult.Valid)
        assertTrue(rule.validate(0.79f) is ValidationResult.Invalid)
        assertTrue(rule.validate(1.01f) is ValidationResult.Invalid)
    }
}
