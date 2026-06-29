package app.readylytics.health.domain.validation

import app.readylytics.health.domain.scoring.ScoringConstants

data class SleepStageFractions(
    val deepFraction: Float,
    val remFraction: Float,
)

class SleepArchitectureValidator : ValidationRule<SleepStageFractions> {
    override val errorMessage: String = "Sleep stages invalid"

    override fun validate(value: SleepStageFractions): ValidationResult {
        if (value.deepFraction > ScoringConstants.MAX_VALID_DEEP_FRACTION) {
            return ValidationResult.Invalid(
                "Deep sleep: max ${(ScoringConstants.MAX_VALID_DEEP_FRACTION * 100).toInt()}%",
            )
        }
        if (value.remFraction > ScoringConstants.MAX_VALID_REM_FRACTION) {
            return ValidationResult.Invalid(
                "REM sleep: max ${(ScoringConstants.MAX_VALID_REM_FRACTION * 100).toInt()}%",
            )
        }
        if (value.deepFraction + value.remFraction > ScoringConstants.MAX_VALID_DEEP_REM_SUM) {
            return ValidationResult.Invalid(
                "Deep+REM: max ${(ScoringConstants.MAX_VALID_DEEP_REM_SUM * 100).toInt()}%",
            )
        }
        return ValidationResult.Valid
    }
}
