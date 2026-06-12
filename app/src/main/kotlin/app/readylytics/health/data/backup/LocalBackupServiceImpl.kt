package app.readylytics.health.data.backup

import android.net.Uri
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupServiceImpl
    @Inject
    constructor(
        private val localBackupManager: LocalBackupManager,
    ) : BackupService {
        override suspend fun createBackup(): Result<Unit> = localBackupManager.createBackup().map { }

        override suspend fun listBackups(): List<BackupFileInfo> = localBackupManager.listBackups()

        override suspend fun deleteBackup(uri: Uri): Result<Unit> = localBackupManager.deleteBackup(uri)

        override suspend fun reencryptBackups(
            oldPassword: String?,
            newPassword: String,
        ): Result<Unit> = localBackupManager.reencryptBackups(oldPassword, newPassword)
    }
