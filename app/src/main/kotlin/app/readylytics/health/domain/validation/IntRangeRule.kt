package app.readylytics.health.domain.validation

class IntRangeRule(
    private val min: Int,
    private val max: Int,
    override val errorMessage: String,
) : ValidationRule<String> {
    override fun validate(value: String): ValidationResult =
        when {
            value.isEmpty() -> ValidationResult.Valid
            value.toIntOrNull() == null -> ValidationResult.Invalid(errorMessage)
            else -> {
                val intValue = value.toInt()
                if (intValue in min..max) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(errorMessage)
                }
            }
        }
}
