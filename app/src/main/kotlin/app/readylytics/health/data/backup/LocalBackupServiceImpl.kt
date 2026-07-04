package app.readylytics.health.data.backup

import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupLocation
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

        override suspend fun deleteBackup(location: BackupLocation): Result<Unit> =
            localBackupManager.deleteBackup(location.toUri())

        override suspend fun reencryptBackups(
            oldPassword: String?,
            newPassword: String,
        ): Result<Unit> = localBackupManager.reencryptBackups(oldPassword, newPassword)
    }

private fun BackupLocation.toUri(): Uri = value.toUri()
