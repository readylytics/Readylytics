package app.readylytics.health.domain.backup

import android.net.Uri

data class BackupFileInfo(
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val uri: Uri,
)
