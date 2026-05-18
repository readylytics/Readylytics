package com.gregor.lauritz.healthdashboard.domain.backup

import android.net.Uri

interface RestoreService {
    suspend fun validate(uri: Uri): Result<Unit>

    suspend fun applyRestore(uri: Uri): RestoreResult
}
