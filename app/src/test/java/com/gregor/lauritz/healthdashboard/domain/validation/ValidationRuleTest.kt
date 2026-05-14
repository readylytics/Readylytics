package com.gregor.lauritz.healthdashboard.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationRuleTest {
    private val rule = IntRangeRule(1, 100, "Value: 1–100")

    @Test
    fun validate_withEmptyString_returnsValid() {
        val result = rule.validate("")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun validate_withValidNumber() {
        val result = rule.validate("50")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun validate_withNumberAtMinBoundary() {
        val result = rule.validate("1")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun validate_withNumberAtMaxBoundary() {
        val result = rule.validate("100")
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun validate_withNumberBelowMin_returnsInvalid() {
        val result = rule.validate("0")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Value: 1–100", (result as ValidationResult.Invalid).message)
    }

    @Test
    fun validate_withNumberAboveMax_returnsInvalid() {
        val result = rule.validate("101")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Value: 1–100", (result as ValidationResult.Invalid).message)
    }

    @Test
    fun validate_withNonNumeric_returnsInvalid() {
        val result = rule.validate("abc")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Value: 1–100", (result as ValidationResult.Invalid).message)
    }

    @Test
    fun validate_withNegativeNumber_returnsInvalid() {
        val result = rule.validate("-50")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun errorMessage_returnsDefinedMessage() {
        assertEquals("Value: 1–100", rule.errorMessage)
    }

    @Test
    fun validate_withBirthdayDayRule() {
        val dayRule = IntRangeRule(1, 31, "Day: 1–31")
        assertTrue(dayRule.validate("15") is ValidationResult.Valid)
        assertTrue(dayRule.validate("0") is ValidationResult.Invalid)
        assertTrue(dayRule.validate("32") is ValidationResult.Invalid)
    }

    @Test
    fun validate_withBirthdayMonthRule() {
        val monthRule = IntRangeRule(1, 12, "Month: 1–12")
        assertTrue(monthRule.validate("6") is ValidationResult.Valid)
        assertTrue(monthRule.validate("0") is ValidationResult.Invalid)
        assertTrue(monthRule.validate("13") is ValidationResult.Invalid)
    }

    @Test
    fun validate_withHeartRateRule() {
        val hrRule = IntRangeRule(1, 220, "HR: 1–220 bpm")
        assertTrue(hrRule.validate("120") is ValidationResult.Valid)
        assertTrue(hrRule.validate("0") is ValidationResult.Invalid)
        assertTrue(hrRule.validate("221") is ValidationResult.Invalid)
    }
}
