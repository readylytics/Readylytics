package app.readylytics.health.core.model.domain.validation

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

data class SleepStageFractions(
    val deepFraction: Float,
    val remFraction: Float,
)

class SleepArchitectureValidator : ValidationRule<SleepStageFractions> {
    override val errorMessage: String = "Sleep stages invalid"

    override fun validate(value: SleepStageFractions): ValidationResult =
        when {
            value.deepFraction > ScoringConstants.MAX_VALID_DEEP_FRACTION ->
                ValidationResult.Invalid("Deep sleep: max ${(ScoringConstants.MAX_VALID_DEEP_FRACTION * 100).toInt()}%")
            value.remFraction > ScoringConstants.MAX_VALID_REM_FRACTION ->
                ValidationResult.Invalid("REM sleep: max ${(ScoringConstants.MAX_VALID_REM_FRACTION * 100).toInt()}%")
            value.deepFraction + value.remFraction > ScoringConstants.MAX_VALID_DEEP_REM_SUM ->
                ValidationResult.Invalid("Deep+REM: max ${(ScoringConstants.MAX_VALID_DEEP_REM_SUM * 100).toInt()}%")
            else -> ValidationResult.Valid
        }
}
