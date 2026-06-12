package app.readylytics.health.domain.backup

sealed interface RestoreResult {
    data object Success : RestoreResult

    data object SuccessRequiresRestart : RestoreResult

    data class Failure(
        val cause: Throwable,
    ) : RestoreResult
}
