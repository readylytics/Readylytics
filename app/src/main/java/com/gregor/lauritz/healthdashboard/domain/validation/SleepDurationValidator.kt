package com.gregor.lauritz.healthdashboard.domain.validation

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants

class SleepDurationValidator : ValidationRule<Int> {
    override val errorMessage: String =
        "Sleep duration: min ${ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES} minutes"

    override fun validate(value: Int): ValidationResult =
        if (value >= ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errorMessage)
        }
}
