package com.gregor.lauritz.healthdashboard.domain.validation

class SyncIntervalHoursRule(
    override val errorMessage: String = "Hours: 1–24",
) : ValidationRule<String> {
    companion object {
        private const val MIN_HOURS = 1
        private const val MAX_HOURS = 24
    }

    override fun validate(value: String): ValidationResult =
        IntRangeRule(MIN_HOURS, MAX_HOURS, errorMessage).validate(value)
}
