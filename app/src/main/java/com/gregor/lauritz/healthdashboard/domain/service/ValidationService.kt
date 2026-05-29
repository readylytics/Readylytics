package com.gregor.lauritz.healthdashboard.domain.service

import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationRule

/**
 * Payload carried in [Result.Success] when validation passes.
 *
 * Empty input still produces a [Result.Success] with [ValidationData.empty].
 */
data class ValidationData(
    val checkedCount: Int,
) {
    companion object {
        val empty: ValidationData = ValidationData(checkedCount = 0)
    }
}

/**
 * Pure-Kotlin service wrapping the existing [ValidationRule] pattern.
 *
 * Constructor-injectable; never throws. Returns [Result]s so success / failure can be
 * composed with the rest of the domain layer. [Result.Failure.reason] holds the first
 * failure message; the full list is exposed via [allErrors] when needed.
 */
class ValidationService {
    /** Run a single rule over a single value. */
    fun <T> validate(
        rule: ValidationRule<T>,
        value: T,
    ): Result<ValidationData> =
        when (val r = rule.validate(value)) {
            is ValidationResult.Valid -> Result.Success(ValidationData(checkedCount = 1))
            is ValidationResult.Invalid -> Result.Failure(r.message, Codes.INVALID)
        }

    /** True iff the rule reports [ValidationResult.Valid] for the given value. */
    fun <T> isValid(
        rule: ValidationRule<T>,
        value: T,
    ): Boolean = rule.validate(value) is ValidationResult.Valid

    /**
     * Apply every rule to its paired value.
     *
     * Empty input returns [Result.Success]. On failure, [Result.Failure.reason] is the
     * first failing message; the complete list is available via [allErrors].
     */
    fun <T> validateAll(checks: List<Pair<ValidationRule<T>, T>>): Result<ValidationData> {
        if (checks.isEmpty()) return Result.Success(ValidationData.empty)
        val errors = collectErrors(checks)
        return if (errors.isEmpty()) {
            Result.Success(ValidationData(checkedCount = checks.size))
        } else {
            Result.Failure(errors.first(), Codes.INVALID)
        }
    }

    /** Convenience: validate the same value against multiple rules. */
    fun <T> validateAgainstAll(
        rules: List<ValidationRule<T>>,
        value: T,
    ): Result<ValidationData> = validateAll(rules.map { it to value })

    /** First failing message across a batch, or null if all pass. */
    fun <T> firstError(checks: List<Pair<ValidationRule<T>, T>>): String? = collectErrors(checks).firstOrNull()

    /** Every failing message across a batch (empty if all pass). */
    fun <T> allErrors(checks: List<Pair<ValidationRule<T>, T>>): List<String> = collectErrors(checks)

    /** Count of failing rules in a batch. */
    fun <T> errorCount(checks: List<Pair<ValidationRule<T>, T>>): Int = collectErrors(checks).size

    /** Returns true iff every check passes. */
    fun <T> allValid(checks: List<Pair<ValidationRule<T>, T>>): Boolean = collectErrors(checks).isEmpty()

    private fun <T> collectErrors(checks: List<Pair<ValidationRule<T>, T>>): List<String> =
        checks.mapNotNull { (rule, value) ->
            val result = rule.validate(value)
            if (result is ValidationResult.Invalid) result.message else null
        }

    /** Stable [Result.Failure.code] values produced by this service. */
    object Codes {
        const val INVALID: String = "INVALID"
    }
}
