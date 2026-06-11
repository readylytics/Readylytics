package app.readylytics.health.domain.service

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.validation.FloatRangeRule
import app.readylytics.health.domain.validation.IntRangeRule
import app.readylytics.health.domain.validation.ValidationResult
import app.readylytics.health.domain.validation.ValidationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ValidationServiceTest {
    private lateinit var service: ValidationService
    private val day = IntRangeRule(1, 31, "Day: 1–31")
    private val month = IntRangeRule(1, 12, "Month: 1–12")
    private val hr = IntRangeRule(1, 220, "HR: 1–220 bpm")
    private val height = FloatRangeRule(120f, 250f, "Height: 120–250 cm")

    @Before
    fun setUp() {
        service = ValidationService()
    }

    // ─── validate (single rule) ──────────────────────────────────────────────
    @Test
    fun validate_validInt_isSuccess() = assertTrue(service.validate(day, "15") is Result.Success)

    @Test
    fun validate_belowMin_isFailure() = assertTrue(service.validate(day, "0") is Result.Failure)

    @Test
    fun validate_aboveMax_isFailure() = assertTrue(service.validate(day, "32") is Result.Failure)

    @Test
    fun validate_minBoundary_isSuccess() = assertTrue(service.validate(day, "1") is Result.Success)

    @Test
    fun validate_maxBoundary_isSuccess() = assertTrue(service.validate(day, "31") is Result.Success)

    @Test
    fun validate_empty_isSuccess() = assertTrue(service.validate(day, "") is Result.Success)

    @Test
    fun validate_nonNumeric_isFailure() = assertTrue(service.validate(day, "abc") is Result.Failure)

    @Test
    fun validate_messagePropagatesIntoReason() {
        val r = service.validate(day, "100") as Result.Failure
        assertEquals("Day: 1–31", r.reason)
    }

    @Test
    fun validate_failure_code_isInvalid() {
        val r = service.validate(day, "100") as Result.Failure
        assertEquals(ValidationService.Codes.INVALID, r.code)
    }

    @Test
    fun validate_floatRule_valid() = assertTrue(service.validate(height, "175") is Result.Success)

    @Test
    fun validate_floatRule_invalid() = assertTrue(service.validate(height, "50") is Result.Failure)

    @Test
    fun validate_floatRule_decimal_valid() = assertTrue(service.validate(height, "175.5") is Result.Success)

    @Test
    fun validate_floatRule_nonNumeric_invalid() = assertTrue(service.validate(height, "tall") is Result.Failure)

    @Test
    fun validate_success_checkedCount_isOne() {
        val r = service.validate(day, "15") as Result.Success
        assertEquals(1, r.data.checkedCount)
    }

    // ─── isValid ─────────────────────────────────────────────────────────────
    @Test
    fun isValid_validInput_isTrue() = assertTrue(service.isValid(day, "10"))

    @Test
    fun isValid_invalidInput_isFalse() = assertFalse(service.isValid(day, "99"))

    @Test
    fun isValid_emptyInput_isTrue() = assertTrue(service.isValid(day, ""))

    @Test
    fun isValid_nonNumeric_isFalse() = assertFalse(service.isValid(day, "abc"))

    @Test
    fun isValid_minBoundary_isTrue() = assertTrue(service.isValid(day, "1"))

    @Test
    fun isValid_belowMin_isFalse() = assertFalse(service.isValid(day, "0"))

    // ─── validateAll ─────────────────────────────────────────────────────────
    @Test
    fun validateAll_empty_isSuccess() {
        val r = service.validateAll<String>(emptyList())
        assertTrue(r is Result.Success)
        assertEquals(0, (r as Result.Success).data.checkedCount)
    }

    @Test
    fun validateAll_allValid_returnsSuccess() {
        val r = service.validateAll(listOf(day to "10", month to "5"))
        assertTrue(r is Result.Success)
        assertEquals(2, (r as Result.Success).data.checkedCount)
    }

    @Test
    fun validateAll_oneInvalid_returnsFailure() {
        val r = service.validateAll(listOf(day to "10", month to "13"))
        assertTrue(r is Result.Failure)
    }

    @Test
    fun validateAll_allInvalid_failureReasonIsFirst() {
        val r = service.validateAll(listOf(day to "0", month to "13", hr to "300")) as Result.Failure
        assertEquals("Day: 1–31", r.reason)
    }

    @Test
    fun validateAll_firstMessage_isFirstFailure() {
        val r = service.validateAll(listOf(day to "0", month to "13")) as Result.Failure
        assertEquals("Day: 1–31", r.reason)
    }

    @Test
    fun validateAll_allErrors_preservesOrder() {
        val checks = listOf(day to "0", month to "13")
        assertEquals(listOf("Day: 1–31", "Month: 1–12"), service.allErrors(checks))
    }

    @Test
    fun validateAll_isFailure_property_true_onInvalid() {
        val r = service.validateAll(listOf(day to "0"))
        assertTrue(r.isFailure)
    }

    @Test
    fun validateAll_isSuccess_property_true_onValid() {
        val r = service.validateAll(listOf(day to "10"))
        assertTrue(r.isSuccess)
    }

    // ─── validateAgainstAll (multiple rules, one value) ──────────────────────
    @Test
    fun validateAgainstAll_singleValue_allRulesPass() {
        val r = service.validateAgainstAll(listOf(day, hr), "15")
        assertTrue(r is Result.Success)
    }

    @Test
    fun validateAgainstAll_singleValue_oneRuleFails() {
        val r = service.validateAgainstAll(listOf(day, hr), "100")
        assertTrue(r is Result.Failure)
    }

    @Test
    fun validateAgainstAll_singleValue_allRulesFail() {
        val r = service.validateAgainstAll(listOf(day, month), "99")
        assertTrue(r is Result.Failure)
    }

    @Test
    fun validateAgainstAll_empty_isSuccess() {
        val r = service.validateAgainstAll<String>(emptyList(), "10")
        assertTrue(r is Result.Success)
    }

    // ─── firstError ──────────────────────────────────────────────────────────
    @Test
    fun firstError_allPass_isNull() {
        assertNull(service.firstError(listOf(day to "10", month to "5")))
    }

    @Test
    fun firstError_oneFail_returnsMessage() {
        assertEquals("Day: 1–31", service.firstError(listOf(day to "0", month to "5")))
    }

    @Test
    fun firstError_emptyChecks_isNull() {
        assertNull(service.firstError<String>(emptyList()))
    }

    @Test
    fun firstError_multipleFail_returnsFirst() {
        assertEquals("Day: 1–31", service.firstError(listOf(day to "0", month to "13")))
    }

    // ─── errorCount ──────────────────────────────────────────────────────────
    @Test
    fun errorCount_allPass_isZero() {
        assertEquals(0, service.errorCount(listOf(day to "10", month to "5")))
    }

    @Test
    fun errorCount_allFail_equalsSize() {
        assertEquals(3, service.errorCount(listOf(day to "0", month to "13", hr to "300")))
    }

    @Test
    fun errorCount_partialFail_isCorrect() {
        assertEquals(1, service.errorCount(listOf(day to "10", month to "13")))
    }

    @Test
    fun errorCount_empty_isZero() {
        assertEquals(0, service.errorCount<String>(emptyList()))
    }

    // ─── allValid ────────────────────────────────────────────────────────────
    @Test
    fun allValid_allPass_isTrue() = assertTrue(service.allValid(listOf(day to "10", month to "5")))

    @Test
    fun allValid_oneFail_isFalse() = assertFalse(service.allValid(listOf(day to "10", month to "13")))

    @Test
    fun allValid_empty_isTrue() = assertTrue(service.allValid<String>(emptyList()))

    @Test
    fun allValid_allFail_isFalse() = assertFalse(service.allValid(listOf(day to "0", month to "13")))

    // ─── Result sealed semantics ─────────────────────────────────────────────
    @Test
    fun result_success_isSuccess_isTrue() = assertTrue(Result.Success(ValidationData.empty).isSuccess)

    @Test
    fun result_failure_isSuccess_isFalse() =
        assertFalse(Result.Failure("err", ValidationService.Codes.INVALID).isSuccess)

    @Test
    fun result_failure_reason_isPreserved() {
        val r = Result.Failure("hello", ValidationService.Codes.INVALID)
        assertEquals("hello", r.reason)
    }

    // ─── custom rule injected into the service ───────────────────────────────
    @Test
    fun customRule_validates_throughService() {
        val rule =
            object : ValidationRule<String> {
                override val errorMessage: String = "must start with 'A'"

                override fun validate(value: String): ValidationResult =
                    if (value.startsWith("A")) ValidationResult.Valid else ValidationResult.Invalid(errorMessage)
            }
        assertTrue(service.isValid(rule, "Apple"))
        assertFalse(service.isValid(rule, "banana"))
    }

    @Test
    fun customRule_appearsInAggregatedFailure() {
        val rule =
            object : ValidationRule<String> {
                override val errorMessage = "fail"

                override fun validate(value: String) = ValidationResult.Invalid("fail")
            }
        val r = service.validateAgainstAll(listOf(rule), "anything") as Result.Failure
        assertEquals("fail", r.reason)
    }

    // ─── boundary / range repetition for high coverage ───────────────────────
    @Test
    fun heartRate_boundary220_isValid() = assertTrue(service.isValid(hr, "220"))

    @Test
    fun heartRate_boundary1_isValid() = assertTrue(service.isValid(hr, "1"))

    @Test
    fun heartRate_just_above_max_isInvalid() = assertFalse(service.isValid(hr, "221"))

    @Test
    fun heartRate_negative_isInvalid() = assertFalse(service.isValid(hr, "-1"))

    @Test
    fun month_boundary12_isValid() = assertTrue(service.isValid(month, "12"))

    @Test
    fun month_boundary1_isValid() = assertTrue(service.isValid(month, "1"))

    @Test
    fun height_decimalAtMin_isValid() = assertTrue(service.isValid(height, "120"))

    @Test
    fun height_decimalAtMax_isValid() = assertTrue(service.isValid(height, "250"))

    @Test
    fun height_overMax_isInvalid() = assertFalse(service.isValid(height, "300"))

    @Test
    fun validateAll_largeBatch_isProcessed() {
        val checks = (0 until 20).map { day to it.toString() }
        val r = service.validateAll(checks)
        assertTrue(r is Result.Failure)
    }

    @Test
    fun validateAll_isDeterministic() {
        val a = service.validateAll(listOf(day to "0", month to "13"))
        val b = service.validateAll(listOf(day to "0", month to "13"))
        assertEquals(a, b)
    }
}
