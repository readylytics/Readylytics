package app.readylytics.health.domain.backup

interface BackupService {
    suspend fun createBackup(): Result<Unit>

    suspend fun listBackups(): List<BackupFileInfo>

    suspend fun deleteBackup(location: BackupLocation): Result<Unit>

    suspend fun reencryptBackups(
        oldPassword: String?,
        newPassword: String,
    ): Result<Unit>
}
