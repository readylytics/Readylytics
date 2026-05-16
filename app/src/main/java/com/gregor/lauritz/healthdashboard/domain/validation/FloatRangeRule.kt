package com.gregor.lauritz.healthdashboard.domain.validation

class FloatRangeRule(
    private val min: Float,
    private val max: Float,
    override val errorMessage: String,
) : ValidationRule<String> {
    override fun validate(value: String): ValidationResult =
        when {
            value.isEmpty() -> ValidationResult.Valid
            value.toFloatOrNull() == null -> ValidationResult.Invalid(errorMessage)
            else -> {
                val floatValue = value.toFloat()
                if (floatValue in min..max) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(errorMessage)
                }
            }
        }
}
