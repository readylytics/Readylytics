package app.readylytics.health.domain.backup

import android.net.Uri

interface RestoreService {
    suspend fun validate(
        uri: Uri,
        password: String? = null,
    ): Result<Unit>

    suspend fun applyRestore(
        uri: Uri,
        password: String? = null,
    ): RestoreResult
}
