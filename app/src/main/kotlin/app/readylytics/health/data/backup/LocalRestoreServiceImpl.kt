package app.readylytics.health.data.backup

import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.data.backup.LocalRestoreManager
import app.readylytics.health.domain.backup.BackupLocation
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRestoreServiceImpl
    @Inject
    constructor(
        private val localRestoreManager: LocalRestoreManager,
    ) : RestoreService {
        override suspend fun validate(
            location: BackupLocation,
            password: String?,
        ): Result<Unit> = localRestoreManager.validate(location.toUri(), password).map { }

        override suspend fun applyRestore(
            location: BackupLocation,
            password: String?,
        ): RestoreResult =
            when (val result = localRestoreManager.applyRestore(location.toUri(), password)) {
                LocalRestoreManager.RestoreResult.Success -> RestoreResult.Success
                LocalRestoreManager.RestoreResult.SuccessRequiresRestart -> RestoreResult.SuccessRequiresRestart
                is LocalRestoreManager.RestoreResult.Failure -> RestoreResult.Failure(result.cause)
            }
    }

private fun BackupLocation.toUri(): Uri = value.toUri()
