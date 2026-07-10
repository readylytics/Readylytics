package app.readylytics.health.domain.validation

class IntStepRangeRule(
    private val min: Int,
    private val max: Int,
    private val step: Int,
    override val errorMessage: String,
) : ValidationRule<String> {
    init {
        require(step > 0) { "step must be > 0" }
    }

    override fun validate(value: String): ValidationResult =
        when {
            value.isEmpty() -> ValidationResult.Valid
            value.toIntOrNull() == null -> ValidationResult.Invalid(errorMessage)
            else -> validate(value.toInt())
        }

    fun validate(value: Int): ValidationResult =
        if (value in min..max && (value - min) % step == 0) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errorMessage)
        }
}
