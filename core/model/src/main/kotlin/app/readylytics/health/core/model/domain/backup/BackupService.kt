package app.readylytics.health.core.model.domain.backup

import kotlinx.coroutines.flow.StateFlow

interface BackupService {
    val operationState: StateFlow<BackupOperationState>

    suspend fun createBackup(): Result<Unit>

    suspend fun listBackups(): List<BackupFileInfo>

    suspend fun deleteBackup(location: BackupLocation): Result<Unit>

    suspend fun rotatePassword(newPassword: String): Result<Unit>

    suspend fun reencryptBackups(
        oldPassword: String?,
        newPassword: String,
    ): Result<Unit>
}
