package com.gregor.lauritz.healthdashboard.domain.validation

import org.junit.Assert.assertTrue
import org.junit.Test

class HrvBoundsValidatorTest {
    private val validator = HrvBoundsValidator()

    @Test
    fun `validate_withinBounds_returnsValid`() {
        val result = validator.validate(50f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_atLowerBound_returnsValid`() {
        val result = validator.validate(5f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_atUpperBound_returnsValid`() {
        val result = validator.validate(250f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_belowMin_returnsInvalid`() {
        val result = validator.validate(4f)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_aboveMax_returnsInvalid`() {
        val result = validator.validate(251f)
        assertTrue(result is ValidationResult.Invalid)
    }
}

class RhrBoundsValidatorTest {
    private val validator = RhrBoundsValidator()

    @Test
    fun `validate_withinBounds_returnsValid`() {
        val result = validator.validate(60f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_atLowerBound_returnsValid`() {
        val result = validator.validate(30f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_atUpperBound_returnsValid`() {
        val result = validator.validate(100f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_belowMin_returnsInvalid`() {
        val result = validator.validate(29f)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_aboveMax_returnsInvalid`() {
        val result = validator.validate(101f)
        assertTrue(result is ValidationResult.Invalid)
    }
}

class SleepDurationValidatorTest {
    private val validator = SleepDurationValidator()

    @Test
    fun `validate_atMinBound_returnsValid`() {
        val result = validator.validate(240)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_aboveMin_returnsValid`() {
        val result = validator.validate(480)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_belowMin_returnsInvalid`() {
        val result = validator.validate(239)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_zero_returnsInvalid`() {
        val result = validator.validate(0)
        assertTrue(result is ValidationResult.Invalid)
    }
}

class SleepEfficiencyValidatorTest {
    private val validator = SleepEfficiencyValidator()

    @Test
    fun `validate_withinBounds_returnsValid`() {
        val result = validator.validate(85f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_atLowerBound_returnsValid`() {
        val result = validator.validate(0f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_atUpperBound_returnsValid`() {
        val result = validator.validate(100f)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_belowMin_returnsInvalid`() {
        val result = validator.validate(-1f)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_aboveMax_returnsInvalid`() {
        val result = validator.validate(101f)
        assertTrue(result is ValidationResult.Invalid)
    }
}

class SleepArchitectureValidatorTest {
    private val validator = SleepArchitectureValidator()

    @Test
    fun `validate_withinAllBounds_returnsValid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.20f, remFraction = 0.25f))
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_deepAtMax_returnsValid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.40f, remFraction = 0.20f))
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_remAtMax_returnsValid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.20f, remFraction = 0.45f))
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate_deepAboveMax_returnsInvalid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.41f, remFraction = 0.20f))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_remAboveMax_returnsInvalid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.20f, remFraction = 0.46f))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_sumAboveMax_returnsInvalid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.40f, remFraction = 0.31f))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validate_sumAtMax_returnsValid`() {
        val result = validator.validate(SleepStageFractions(deepFraction = 0.35f, remFraction = 0.35f))
        assertTrue(result is ValidationResult.Valid)
    }
}
