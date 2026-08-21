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
}
