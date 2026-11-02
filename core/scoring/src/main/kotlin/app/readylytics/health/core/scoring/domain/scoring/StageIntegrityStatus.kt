package app.readylytics.health.core.scoring.domain.scoring

enum class StageIntegrityStatus {
    VALID,
    NO_BREAKDOWN,
    IMPLAUSIBLE_RATIO,
}

object StageIntegrityEvaluator {
    fun evaluate(
        durationMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int,
        validation: ScoringCalculator.NightValidationResult,
    ): StageIntegrityStatus = when {
        durationMinutes > 0 && deepMinutes == 0 && remMinutes == 0 ->
            StageIntegrityStatus.NO_BREAKDOWN
        !validation.stagesValid || validation.stagesSuspicious ->
            StageIntegrityStatus.IMPLAUSIBLE_RATIO
        else ->
            StageIntegrityStatus.VALID
    }
}
