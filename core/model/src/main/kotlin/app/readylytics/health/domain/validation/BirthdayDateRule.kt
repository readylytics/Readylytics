package app.readylytics.health.domain.validation

import java.time.Clock
import java.time.LocalDate

class BirthdayDateRule(
    private val clock: Clock = Clock.systemDefaultZone(),
) : ValidationRule<LocalDate> {
    override val errorMessage: String = "Birthday must be in the past"

    override fun validate(value: LocalDate): ValidationResult {
        val today = LocalDate.now(clock)
        val minYear = 1900

        return when {
            value > today -> ValidationResult.Invalid(errorMessage)
            value.year < minYear -> ValidationResult.Invalid("Year must be 1900 or later")
            else -> ValidationResult.Valid
        }
    }
}
