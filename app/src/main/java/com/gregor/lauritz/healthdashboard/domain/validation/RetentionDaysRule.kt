package com.gregor.lauritz.healthdashboard.domain.validation

class RetentionDaysRule(
    override val errorMessage: String = "Days: 1–3,650",
) : ValidationRule<String> {
    companion object {
        private const val MIN_DAYS = 1
        private const val MAX_DAYS = 3650
    }

    override fun validate(value: String): ValidationResult =
        IntRangeRule(MIN_DAYS, MAX_DAYS, errorMessage).validate(value)
}
