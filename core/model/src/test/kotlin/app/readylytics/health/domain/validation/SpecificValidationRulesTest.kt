package app.readylytics.health.domain.validation

import org.junit.Test
import kotlin.test.assertEquals

class SpecificValidationRulesTest {
    // FloatRangeRule tests

    @Test
    fun floatRangeRule_validValue_returnsValid() {
        val rule = FloatRangeRule(0f, 100f, "error")
        val result = rule.validate(50.5f)
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun floatRangeRule_invalidValue_belowMin_returnsInvalid() {
        val rule = FloatRangeRule(0f, 100f, "error")
        val result = rule.validate(-1f)
        assertEquals(ValidationResult.Invalid("error"), result)
    }

    // RasScalingFactorRule tests (0.1–0.3)

    @Test
    fun rasScalingFactorRule_validValue_0_2_returnsValid() {
        val rule = RasScalingFactorRule()
        val result = rule.validate("0.2")
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun rasScalingFactorRule_invalidValue_0_05_returnsInvalid() {
        val rule = RasScalingFactorRule()
        val result = rule.validate("0.05")
        assertEquals(ValidationResult.Invalid("RAS: 0.1–0.3"), result)
    }

    // RetentionDaysRule tests (90–1800 days = 3–60 months)

    @Test
    fun retentionDaysRule_validValue_365_returnsValid() {
        val rule = RetentionDaysRule()
        val result = rule.validate("365")
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun retentionDaysRule_invalidValue_0_returnsInvalid() {
        val rule = RetentionDaysRule()
        val result = rule.validate("0")
        assertEquals(ValidationResult.Invalid("Months: 3–60"), result)
    }

    // StepGoalRule tests (0–100,000)

    @Test
    fun stepGoalRule_validValue_5000_returnsValid() {
        val rule = StepGoalRule()
        val result = rule.validate("5000")
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun stepGoalRule_invalidValue_100001_returnsInvalid() {
        val rule = StepGoalRule()
        val result = rule.validate("100001")
        assertEquals(ValidationResult.Invalid("Steps: 0–100,000"), result)
    }

    // SyncIntervalHoursRule tests (1–24)

    @Test
    fun syncIntervalHoursRule_validValue_12_returnsValid() {
        val rule = SyncIntervalHoursRule()
        val result = rule.validate("12")
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun syncIntervalHoursRule_invalidValue_0_returnsInvalid() {
        val rule = SyncIntervalHoursRule()
        val result = rule.validate("0")
        assertEquals(ValidationResult.Invalid("Hours: 1–24"), result)
    }

    // TrimpParameterRule tests (dynamic range via FloatRangeRule)

    @Test
    fun trimpParameterRule_validValue_0_5_returnsValid() {
        val rule = TrimpParameterRule(0f, 1f, "error")
        val result = rule.validate("0.5")
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun trimpParameterRule_invalidValue_1_5_returnsInvalid() {
        val rule = TrimpParameterRule(0f, 1f, "error")
        val result = rule.validate("1.5")
        assertEquals(ValidationResult.Invalid("error"), result)
    }
}
