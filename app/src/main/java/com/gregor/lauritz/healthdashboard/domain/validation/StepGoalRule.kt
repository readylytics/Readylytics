package com.gregor.lauritz.healthdashboard.domain.validation

class StepGoalRule(
    override val errorMessage: String = "Steps: 0–100,000",
) : ValidationRule<String> {
    companion object {
        private const val MIN_STEPS = 0
        private const val MAX_STEPS = 100000
    }

    override fun validate(value: String): ValidationResult =
        IntRangeRule(MIN_STEPS, MAX_STEPS, errorMessage).validate(value)
}
