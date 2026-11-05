package app.readylytics.health.core.model.domain.validation

import java.time.Clock
import java.time.LocalDate

class BirthdayDateRule(
    private val clock: Clock,
) : ValidationRule<LocalDate> {
    override val errorMessage: String = "Birthday must be in the past"

    override fun validate(value: LocalDate): ValidationResult {
        val bounds = BirthdayBounds.at(clock)

        return when {
            value > bounds.maxDate -> ValidationResult.Invalid(errorMessage)
            value.year < bounds.minYear -> ValidationResult.Invalid("Year must be ${bounds.minYear} or later")
            else -> ValidationResult.Valid
        }
    }
}
