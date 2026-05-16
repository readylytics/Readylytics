package com.gregor.lauritz.healthdashboard.domain.validation

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants

class RhrBoundsValidator : ValidationRule<Float> {
    override val errorMessage: String =
        "RHR: ${ScoringConstants.MIN_VALID_SLEEP_RHR.toInt()}–" +
            "${ScoringConstants.MAX_VALID_SLEEP_RHR.toInt()} bpm"

    override fun validate(value: Float): ValidationResult =
        if (value in ScoringConstants.MIN_VALID_SLEEP_RHR..ScoringConstants.MAX_VALID_SLEEP_RHR) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errorMessage)
        }
}
