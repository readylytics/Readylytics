package app.readylytics.health.domain.validation

import org.junit.Test
import kotlin.test.assertTrue

class HrrToleranceRuleTest {
    private val rule = HrrToleranceRule()

    @Test
    fun `15 and 60 are valid`() {
        assertTrue(rule.validate("15") is ValidationResult.Valid)
        assertTrue(rule.validate("60") is ValidationResult.Valid)
    }

    @Test
    fun `14 and 61 are invalid`() {
        assertTrue(rule.validate("14") is ValidationResult.Invalid)
        assertTrue(rule.validate("61") is ValidationResult.Invalid)
    }
}
