package app.readylytics.health.core.model.domain.backup

interface RestoreService {
    suspend fun validate(
        location: BackupLocation,
        password: String? = null,
    ): Result<Unit>

    suspend fun applyRestore(
        location: BackupLocation,
        password: String? = null,
    ): RestoreResult
}
