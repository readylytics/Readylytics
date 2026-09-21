package app.readylytics.health.data.backup

import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.core.model.domain.backup.BackupFileInfo
import app.readylytics.health.core.model.domain.backup.BackupLocation
import app.readylytics.health.core.model.domain.backup.BackupOperationState
import app.readylytics.health.core.model.domain.backup.BackupService
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupServiceImpl
    @Inject
    constructor(
        private val localBackupManager: LocalBackupManager,
        private val backupRotationService: BackupRotationService,
    ) : BackupService {
        override val operationState: StateFlow<BackupOperationState> =
            backupRotationService.operationState

        override suspend fun createBackup(): Result<Unit> = localBackupManager.createBackup().map { }

        override suspend fun listBackups(): List<BackupFileInfo> = localBackupManager.listBackups()

        override suspend fun deleteBackup(location: BackupLocation): Result<Unit> =
            localBackupManager.deleteBackup(location.toUri())

        override suspend fun rotatePassword(newPassword: String): Result<Unit> =
            backupRotationService.rotatePassword(newPassword)

        override suspend fun reencryptBackups(
            oldPassword: String?,
            newPassword: String,
        ): Result<Unit> = rotatePassword(newPassword)
    }

private fun BackupLocation.toUri(): Uri = value.toUri()
