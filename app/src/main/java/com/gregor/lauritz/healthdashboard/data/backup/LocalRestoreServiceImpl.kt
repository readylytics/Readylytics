package com.gregor.lauritz.healthdashboard.data.backup

import android.net.Uri
import com.gregor.lauritz.healthdashboard.domain.backup.LocalRestoreManager
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreResult
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRestoreServiceImpl
    @Inject
    constructor(
        private val localRestoreManager: LocalRestoreManager,
    ) : RestoreService {
        override suspend fun validate(uri: Uri): Result<Unit> = localRestoreManager.validate(uri).map { }

        override suspend fun applyRestore(uri: Uri): RestoreResult =
            when (val result = localRestoreManager.applyRestore(uri)) {
                LocalRestoreManager.RestoreResult.Success -> RestoreResult.Success
                LocalRestoreManager.RestoreResult.SuccessRequiresRestart -> RestoreResult.SuccessRequiresRestart
                is LocalRestoreManager.RestoreResult.Failure -> RestoreResult.Failure(result.cause)
            }
    }
