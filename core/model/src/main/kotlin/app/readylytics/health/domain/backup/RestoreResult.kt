package app.readylytics.health.domain.backup

enum class RestoreStage {
    VALIDATION,
    DATABASE,
    PREFERENCES,
    CARD_CONFIGURATION,
    WORK_SCHEDULING,
}

sealed interface RestoreResult {
    data object Success : RestoreResult

    data object SuccessRequiresRestart : RestoreResult

    data class PartialSuccessRequiresRestart(
        val failedStage: RestoreStage,
        val cause: Throwable,
    ) : RestoreResult

    data class Failure(
        val cause: Throwable,
    ) : RestoreResult
}
