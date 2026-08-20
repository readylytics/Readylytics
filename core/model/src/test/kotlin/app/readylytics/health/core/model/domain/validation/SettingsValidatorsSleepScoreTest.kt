package app.readylytics.health.core.model.domain.validation

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorsSleepScoreTest {
    private fun isValid(value: Int) =
        SettingsValidators.HYPERSOMNIA_ONSET_PERCENT_RULE.validate(value) is ValidationResult.Valid

    @Test
    fun `accepts the documented step values`() {
        listOf(100, 105, 110, 115, 120, 125).forEach { assertTrue("$it", isValid(it)) }
    }

    @Test
    fun `rejects off-step and out-of-range values`() {
        listOf(103, 99, 130).forEach { assertTrue("$it", !isValid(it)) }
    }
}
