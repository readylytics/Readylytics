package app.readylytics.health.domain.validation

class SleepEfficiencyValidator : ValidationRule<Float> {
    override val errorMessage: String = "Sleep efficiency: 0–100%"

    override fun validate(value: Float): ValidationResult =
        if (value in 0f..100f) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errorMessage)
        }
}
