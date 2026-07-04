package app.readylytics.health.domain.validation

interface ValidationRule<T> {
    fun validate(value: T): ValidationResult

    val errorMessage: String
}
