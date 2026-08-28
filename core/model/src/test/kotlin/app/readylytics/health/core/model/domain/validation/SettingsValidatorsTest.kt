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
}
