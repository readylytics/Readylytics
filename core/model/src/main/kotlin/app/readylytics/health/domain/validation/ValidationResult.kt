package app.readylytics.health.domain.validation

sealed class ValidationResult {
    object Valid : ValidationResult()

    data class Invalid(
        val message: String,
    ) : ValidationResult()
}
