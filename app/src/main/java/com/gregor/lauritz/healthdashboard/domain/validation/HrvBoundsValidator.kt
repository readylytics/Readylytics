package com.gregor.lauritz.healthdashboard.domain.validation

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants

class HrvBoundsValidator : ValidationRule<Float> {
    override val errorMessage: String =
        "HRV: ${ScoringConstants.MIN_VALID_RMSSD_MS}–" +
            "${ScoringConstants.MAX_VALID_RMSSD_MS} ms"

    override fun validate(value: Float): ValidationResult =
        if (value in ScoringConstants.MIN_VALID_RMSSD_MS..ScoringConstants.MAX_VALID_RMSSD_MS) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errorMessage)
        }
}
