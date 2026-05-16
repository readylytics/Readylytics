package com.gregor.lauritz.healthdashboard.domain.validation

class PaiScalingFactorRule(
    override val errorMessage: String = "PAI: 0.1–0.3",
) : ValidationRule<String> {
    companion object {
        private const val MIN_FACTOR = 0.1f
        private const val MAX_FACTOR = 0.3f
    }

    override fun validate(value: String): ValidationResult =
        FloatRangeRule(MIN_FACTOR, MAX_FACTOR, errorMessage).validate(value)
}
