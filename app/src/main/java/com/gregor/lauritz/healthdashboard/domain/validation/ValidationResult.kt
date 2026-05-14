package com.gregor.lauritz.healthdashboard.domain.validation

sealed class ValidationResult {
    object Valid : ValidationResult()

    data class Invalid(
        val message: String,
    ) : ValidationResult()
}
