package app.readylytics.health.domain.backup

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
