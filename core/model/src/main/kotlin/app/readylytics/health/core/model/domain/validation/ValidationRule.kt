package app.readylytics.health.core.model.domain.validation

interface ValidationRule<T> {
    fun validate(value: T): ValidationResult

    val errorMessage: String
}
