package app.readylytics.health.data.backup

import android.net.Uri
import app.readylytics.health.data.backup.LocalRestoreManager
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
            uri: Uri,
            password: String?,
        ): Result<Unit> = localRestoreManager.validate(uri, password).map { }

        override suspend fun applyRestore(
            uri: Uri,
            password: String?,
        ): RestoreResult =
            when (val result = localRestoreManager.applyRestore(uri, password)) {
                LocalRestoreManager.RestoreResult.Success -> RestoreResult.Success
                LocalRestoreManager.RestoreResult.SuccessRequiresRestart -> RestoreResult.SuccessRequiresRestart
                is LocalRestoreManager.RestoreResult.Failure -> RestoreResult.Failure(result.cause)
            }
    }
