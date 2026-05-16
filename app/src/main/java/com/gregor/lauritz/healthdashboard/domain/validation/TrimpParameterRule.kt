package com.gregor.lauritz.healthdashboard.domain.validation

class TrimpParameterRule(
    private val min: Float,
    private val max: Float,
    override val errorMessage: String,
) : ValidationRule<String> {
    override fun validate(value: String): ValidationResult = FloatRangeRule(min, max, errorMessage).validate(value)
}
