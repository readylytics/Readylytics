package com.gregor.lauritz.healthdashboard.domain.backup

import android.net.Uri

interface BackupService {
    suspend fun createBackup(): Result<Unit>

    suspend fun listBackups(): List<BackupFileInfo>

    suspend fun deleteBackup(uri: Uri): Result<Unit>

    suspend fun reencryptBackups(
        oldPassword: String?,
        newPassword: String,
    ): Result<Unit>
}
