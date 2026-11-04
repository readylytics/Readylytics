package app.readylytics.health.core.model.domain.model

/**
 * Sealed hierarchy of typed domain errors replacing stringly-typed codes and reasons.
 */
sealed interface DomainError {
    val code: String
    val message: String

    data object InvalidDate : DomainError {
        override val code: String = "INVALID_DATE"
        override val message: String = "Cannot select future dates"
    }

    data object CardGenerationError : DomainError {
        override val code: String = "CARD_GENERATION_ERROR"
        override val message: String = "Failed to build dashboard data"
    }

    data class Generic(
        override val code: String = Result.UNKNOWN_CODE,
        override val message: String,
    ) : DomainError
}
