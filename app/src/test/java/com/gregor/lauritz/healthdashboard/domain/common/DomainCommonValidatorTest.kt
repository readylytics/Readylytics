package com.gregor.lauritz.healthdashboard.domain.common

import org.junit.Test
import kotlin.test.assertTrue

class DomainCommonValidatorTest {
    // CircadianThresholdValidator tests

    @Test
    fun circadianThresholdValidator_nullValue_returnsSuccess() {
        val result = CircadianThresholdValidator.validate(null)
        assertTrue(result.isSuccess)
    }

    @Test
    fun circadianThresholdValidator_validValue_0_returnsSuccess() {
        val result = CircadianThresholdValidator.validate(0)
        assertTrue(result.isSuccess)
    }

    @Test
    fun circadianThresholdValidator_validValue_45_returnsSuccess() {
        val result = CircadianThresholdValidator.validate(45)
        assertTrue(result.isSuccess)
    }

    @Test
    fun circadianThresholdValidator_validValue_90_returnsSuccess() {
        val result = CircadianThresholdValidator.validate(90)
        assertTrue(result.isSuccess)
    }

    @Test
    fun circadianThresholdValidator_invalidValue_91_returnsFailure() {
        val result = CircadianThresholdValidator.validate(91)
        assertTrue(result.isFailure)
    }
}
