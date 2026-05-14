package com.gregor.lauritz.healthdashboard.domain.validation

interface ValidationRule<T> {
    fun validate(value: T): ValidationResult

    val errorMessage: String
}
