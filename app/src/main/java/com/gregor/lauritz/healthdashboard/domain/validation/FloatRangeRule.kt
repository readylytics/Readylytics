package com.gregor.lauritz.healthdashboard.domain.validation

class FloatRangeRule(
    private val min: Float,
    private val max: Float,
    override val errorMessage: String,
) : ValidationRule<String> {
    fun validate(value: Float): ValidationResult =
        if (value in min..max) ValidationResult.Valid else ValidationResult.Invalid(errorMessage)

    override fun validate(value: String): ValidationResult =
        value.toFloatOrNull()?.let { validate(it) } ?: ValidationResult.Invalid(errorMessage)
}
